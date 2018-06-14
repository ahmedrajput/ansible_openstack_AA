package se.seamless.ers.components.dataaggregator.aggregator

import groovy.transform.EqualsAndHashCode
import groovy.util.logging.Log4j

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/**
 * Groups reseller transactions of profile "TOPUP", "REVERSE_TOPUP", "CREDIT_TRANSFER", "REVERSE_CREDIT_TRANSFER" by: 
 * <ul>
 * <li>date</li>
 * <li>resellerId</li>
 * <li>resellerMSISDN</li>
 * <li>resellerName</li>
 * <li>accountTypeId</li>
 * <li>reseller level</li>
 * </ul>
 * Aggregates count of successful transactions with coresponding amounts.
 * Used by reports:
 * <ul>
 * <li>STD_BIR_2005.rptdesign - Top Resellers</li>
 * </ul>
 *
 * @author Michał Podsiedzik
 */
@Log4j
@DynamicMixin
public class StdTopTenResellersAggregator extends AbstractAggregator
{
	static final def TABLE = "std_top_ten_resellers_aggregation"
	static final def MONETARY_PROFILES = [
		"TOPUP",
		"REVERSE_TOPUP",
		"CREDIT_TRANSFER",
		"REVERSE_CREDIT_TRANSFER",
        "VOT_PURCHASE",
        "VOS_PURCHASE",
        "REVERSE_VOT_PURCHASE",
        "REVERSE_VOS_PURCHASE",
        "PRODUCT_RECHARGE"

    ]

	@Value('${StdTopTenResellersAggregator.batch:1000}')
	int limit

	@EqualsAndHashCode
	private static class Key
	{
		Date date
		String resellerId
		String resellerMSISDN
		String resellerName
		String accountTypeId
		String resellerTypeId
	}

	@Transactional
	@Scheduled(cron = '${StdTopTenResellersAggregator.cron:0 0/30 * * * ?}')
	public void aggregate()
	{
		def transactions = getTransactions(limit)
		if (transactions)
		{
			def aggregation = findAllowedProfiles(transactions, MONETARY_PROFILES).findAll(successfulTransactions)
					.collect(transactionInfo)
					.findAll()
					.groupBy(key)
					.collect(statistics)
			updateAggregation(aggregation)
			updateCursor(transactions)
			schedule()
		}
	}

	private def transactionInfo =
	{
		log.debug("Processing transaction: ${it}")
		def tr = parser.parse(it.getJSON()).getAsJsonObject()
		def principal = it.getProfile().equals(MONETARY_PROFILES[3]) ? asJson(tr, "senderPrincipal") : asJson(tr, "principal")
		def isRetrieveStock=it.getErsTransaction()?.getTransactionProperties()?.get("retrieveStock");
		def resellerId =  asString(principal, "resellerId")
		if (isRetrieveStock == "true"){
		principal=asJson(tr, "senderPrincipal")
		resellerId=asString(principal,"parentResellerId")
		}
		
		if(resellerId)
		{
			def date = it.getEndTime().clone().clearTime()
			def resellerMSISDN = asString(principal, "resellerMSISDN")
			def resellerName = asString(principal, "resellerName")
			def resellerTypeId = asStringOrDefault(principal, "resellerTypeId")
			def accountTypeId = getSenderAccountTypeIdWRTProfile(tr,it.profile)
			def amount = collectAmount(tr,it.profile)
			
			if (isReversed(it.profile))
			{
				amount = amount.negate()
			}
			
			if (isRetrieveStock == "true")
			{
				resellerName=asString(principal,"parentResellerName")
				def receiverPrincipal = resellerTypeId=asJson(tr,"receiverPrincipal")
				resellerTypeId= asStringOrDefault(receiverPrincipal,"resellerTypeId")
				amount = amount.negate()
			}

			[date: date, resellerId: resellerId, resellerMSISDN: resellerMSISDN, resellerName: resellerName, accountTypeId: accountTypeId, resellerTypeId: resellerTypeId, amount: amount]
		}
	}

	private def key =
	{
		log.debug("Creating key from ${it}")
		new Key(date: it.date, resellerMSISDN: it.resellerMSISDN, resellerId: it.resellerId, resellerName: it.resellerName, accountTypeId: it.accountTypeId, resellerTypeId: it.resellerTypeId)
	}

	private def statistics =
	{	key, list ->
		[key : key, successful: list.size(), amount:  list.sum { it.amount }]
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{
			def sql = "INSERT INTO ${TABLE} (aggregationDate, resellerId, resellerName, resellerMSISDN, accountTypeId, resellerTypeId, successful, amount) values (?, ?, ?, ?, ?, ?, ?, ?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{	ps, i ->
					def row = aggregation[i]
					ps.setDate(1, toSqlDate(row.key.date))
					ps.setString(2, row.key.resellerId)
					ps.setString(3, row.key.resellerName)
					ps.setString(4, row.key.resellerMSISDN)
					ps.setString(5, row.key.accountTypeId)
					ps.setString(6, row.key.resellerTypeId)
					ps.setInt(7, row.successful)
					ps.setBigDecimal(8, row.amount)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}

}

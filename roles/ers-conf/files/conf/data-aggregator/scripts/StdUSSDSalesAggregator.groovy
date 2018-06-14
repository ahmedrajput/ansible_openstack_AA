package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


@Log4j
@DynamicMixin
public class StdUSSDSalesAggregator extends AbstractAggregator
{

	static final def TABLE = "std_ussd_sales_aggregation"

	@Value('${StdUSSDSalesAggregator.batch:1000}')
	int limit

	static final def ALLOWED_PROFILES = [
		"CREDIT_TRANSFER",
		"TOPUP",
		"DATA_BUNDLE",
        "VOT_PURCHASE",
        "VOS_PURCHASE",
        "PRODUCT_RECHARGE",
		"VAS_BUNDLE"
	]

	@EqualsAndHashCode
	@ToString
	private static class Key
	{
		Date date
		String profileId
		String senderMSISDN
		String receiverMSISDN
		String currency
	}

	@Transactional
	@Scheduled(cron = '${StdUSSDSalesAggregator.cron:0 0/1 * * * ?}')
	public void aggregate()
	{
		def transactions = getTransactions(limit)
		if (transactions)
		{
			def aggregation = findAllowedProfiles(transactions, ALLOWED_PROFILES)
                    .findAll(successfulTransactions)
					.collect(transactionInfo)
                    .findAll{it} //To filter null values to avoid Null pointer exception in group by Key.
					.groupBy(key)
					.collect(statistics)

			updateAggregation(aggregation)
			updateCursor(transactions)
			schedule()
		}
	}

	private def transactionInfo =
	{
		log.info("Processing ussdsales transaction: ${it}")
        def tr = parser.parse(it.getJSON())?.getAsJsonObject()
		def senderMSISDN = getSenderMSISDN(tr)
		def transactionrofile = it.profile
		
		if ((transactionrofile=="CREDIT_TRANSFER") ||(senderMSISDN!=null && transactionrofile!="CREDIT_TRANSFER")){
			//For some top level operator senderMSISDN may be null allow them only for manual adjustment/credit transfer and for other cases
			// if senderMSISDN is not specified then it is P2P TOPUP - filter it out
			def date = gson.fromJson(tr.get("startTime"), Date.class)?.clearTime()
			def profileId = it.profile
            def receiverMSISDN = getReceiverMSISDN(tr)
			def amount = collectAmount(tr,it.profile)
			def currency = collectCurrency(tr)
			[date: date, profileId: profileId, senderMSISDN: senderMSISDN, receiverMSISDN: receiverMSISDN, amount: amount, currency: currency]
		}
	}

	private key =
	{
		log.debug("Creating key from ${it}")
		new Key(date:it.date, profileId: it.profileId, senderMSISDN: it.senderMSISDN, receiverMSISDN: it.receiverMSISDN, currency: it.currency)
	}

	private statistics =
	{	key, list ->
		[key:key, quantity:list.size(), amount:list.sum { it.amount ?: 0 }]
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation) {
			def sql = "INSERT INTO ${TABLE} (aggregationDate, profileId, senderMSISDN, receiverMSISDN, quantity, amount, currency) VALUES (?, ?, ?, ?, ?, ?, ?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues: { ps, i ->
					ps.setDate(1, toSqlDate(aggregation[i].key.date))
					ps.setString(2, aggregation[i].key.profileId)
					ps.setString(3, aggregation[i].key.senderMSISDN)
					ps.setString(4, aggregation[i].key.receiverMSISDN)
					ps.setInt(5, aggregation[i].quantity)
					ps.setBigDecimal(6, aggregation[i].amount)
					ps.setString(7, aggregation[i].key.currency)
				},
				getBatchSize: { aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}

}

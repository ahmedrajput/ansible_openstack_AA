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


/**
 * Groups transactions by:
 * <ul>
 * <li>date</li>
 * <li>channel</li>
 * <li>accountTypeId</li>
 * <li>profileId</li>
 * <li>currency</li>
 * </ul>
 * Aggregates count of successful with coresponding amounts.
 * Used by reports:
 * <ul>
 * <li>STD_BIR_2003.rptdesign - Transactions Statistics Report for Marketing</li>
 * <li>STD_BIR_2010.rptdesign - Transactions Statistics Report for Support</li>
 * </ul>
 *
 * @author Bartłomiej Wachowski
 */
@Log4j
@DynamicMixin
public class StdTransactionsStatisticsAggregator extends AbstractAggregator
{
	static final def TABLE = "std_transaction_statistics_aggregation"
 	static final def VOUCHER_CATEGORIES = [

            "VOUCHER_PURCHASE",
            "VOS_PURCHASE",
            "VOT_PURCHASE",
            "PURCHASE"
        ]

	@Value('${StdTransactionsStatisticsAggregator.negateAmount:true}')
    boolean negateAmount


	@Value('${StdTransactionsStatisticsAggregator.batch:1000}')
	int limit

	@EqualsAndHashCode
	@ToString
	private static class Key
	{
		Date date
		String channel
		String accountTypeId
		String profileId
		String currency
	}
	@Transactional
	@Scheduled(cron = '${StdTransactionsStatisticsAggregator.cron:0 0/30 * * * ?}')
	public void aggregate()
	{
		def transactions = getTransactions(limit)
		if (transactions)
		{
			def aggregation = transactions.collect(transactionInfo)
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
		
		def date = it.getStartTime().clone().clearTime()
		def tr = parser.parse(it.getJSON()).getAsJsonObject()
		log.debug("TransactionData="+tr)

        def ersReference = tr.get("ersReference")?.getAsString()
        //log.info("ersReference : ---- " +ersReference)
        def resultCode = it.resultCode
		def channel = asString(tr, "channel")
        def profileId = translatedTransactionProfile(channel,it.profile)
		def accountTypeId = getSenderAccountTypeIdWRTProfile(tr,profileId)
        def amount = collectAmount(tr,profileId)
		def currency = collectCurrency(tr)
		
		def productSKU = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("productSKU")?.getAsString()

		def isRetrieveStock=it.getErsTransaction()?.getTransactionProperties()?.get("retrieveStock");
		if (isRetrieveStock == "true")
		{
			profileId = "RETRIEVE_STOCK"
		}
		log.debug("Transation productSKU="+productSKU+",productId="+tr.get("productId")?.getAsString()+",profileId="+tr.get("profileId")?.getAsString())
		
		def productId = tr.get("productId")?.getAsString()
		
		def retrieveStock=it.getErsTransaction()?.getTransactionProperties()?.get("retrieveStock");
		
		if(VOUCHER_CATEGORIES.contains(profileId)){
                        tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
                        log.debug("process transaction row:" + it)
                        amount = getBigDecimalFieldFromAnyField(it,"value","amount")

                    }
                }
		if (retrieveStock == "true")
		{
			profileId = "RETRIEVE_STOCK"
			amount = amount.negate()
		}
		
		if("CASHIN"==productId || "MM2ERS"==productId){
			profileId=productId
		}
		
		if (isReversed (profileId) && negateAmount)
		{
			amount = amount.negate()
		}
		[date: date, channel: channel, accountTypeId: accountTypeId, profileId: profileId, amount: amount, currency: currency, resultCode: resultCode]
	}

	private def key =
	{
		log.debug("Creating key from ${it}")
		new Key(date: it.date, channel: it.channel, accountTypeId: it.accountTypeId, profileId: it.profileId, currency: it.currency)
	}

	private def statistics =
	{	key, list ->
		def amountSuccessful = 0
		def successful = 0
		list.each
		{
			if (it.resultCode == 0)
			{
				amountSuccessful += it.amount
				successful++
			}
		}
		[key: key, successful: successful, amountSuccessful: amountSuccessful]
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{
			def sql = "INSERT INTO ${TABLE} (aggregationDate, channel, accountTypeId, profileId, successful, amountSuccessful, currency) VALUES (?, ?, ?, ?, ?, ?, ?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{ ps, i ->
					def row = aggregation[i]
					ps.setDate(1, toSqlDate(row.key.date))
					ps.setString(2, row.key.channel)
					ps.setString(3, row.key.accountTypeId)
					ps.setString(4, row.key.profileId)
					ps.setInt(5, row.successful)
					ps.setBigDecimal(6, row.amountSuccessful)
					ps.setString(7, row.key.currency)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}

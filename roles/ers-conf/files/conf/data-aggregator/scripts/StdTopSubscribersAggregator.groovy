package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.util.Date
import java.util.List
import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

/**
 * This aggregator will be used to aggregate data from all P2P transactions in to std_top_subscribers_aggregation table  
 * @author Bilal Mirza
 */

@Log4j
@DynamicMixin
public class StdTopSubscribersAggregator extends AbstractAggregator
{
	static final def TABLE = "std_top_subscribers_aggregation"
	static final def ALLOWED_PROFILES = ["TOPUP","PRODUCT_RECHARGE"]

	@Value('${StdTopSubscribersAggregator.batch:1000}')
	int limit

	@EqualsAndHashCode
	@ToString
	private static class Key implements Comparable<Key>
	{
		Date date
		String subscriberMSISDN
		String transactionProfile
		String currency

		int compareTo(Key other)
		{
			date <=> other.date ?: subscriberMSISDN <=> other.subscriberMSISDN ?: transactionProfile <=> other.transactionProfile ?: currency <=> other.currency
		}
	}

	@Transactional
	@Scheduled(cron = '${StdTopSubscribersAggregator.cron:0 0/30 * * * ?}')
	public void aggregate()
	{
		def txTransactions
		log.info("\n\nStdTopSubscribersAggregator started feeding transactions\n\n")

		use(TimeCategory)
		{
			txTransactions = getTransactions(new Date() - 1.minute, limit)
		}

		if (!txTransactions)
		{
			log.info("No transactions returned")
			return
		}



		def matchedTransactions = txTransactions.findAll
		{
			it != null && it.resultCode == 0 && ALLOWED_PROFILES.contains(it.getProfile())
		}

		if (matchedTransactions)
		{

			def sendertransactions = matchedTransactions.collect
					{
						def date = it.getEndTime().clone().clearTime()
						def tr = parser.parse(it.getJSON()).getAsJsonObject()
						def productId = tr.get("productId")?.getAsString()
						def principal = asJson(tr, "senderPrincipal")
						def profileId = it.getProfile()
						def subscriberMSISDN = asString(principal, "subscriberMSISDN")
						def currency = getStringFieldFromAnyField(tr, "currency", "requestedTopupAmount")
						def amount = getBigDecimalFieldFromAnyField(tr, "value", "requestedTopupAmount") ?: 0
						def senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()
						[date: date, subscriberMSISDN: subscriberMSISDN,transactionProfile:productId, transactionAmount: amount, currency: currency]
					}
					.findAll
					{
						it!= null && it.transactionProfile=="P2P"
					}
					.groupBy
					{
						new Key(date:it.date, subscriberMSISDN:it.subscriberMSISDN, transactionProfile:it.transactionProfile, currency:it.currency)
					}
					.collect()
			{ key, list ->
				[key: key, transactionCount: list.size(), transactionAmount: list.sum {it.transactionAmount ?: 0}]
			}
			updateAggregation(sendertransactions)
		}
		updateCursor(txTransactions)
		schedule(50, TimeUnit.MILLISECONDS)
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{

			def sql = "INSERT INTO ${TABLE} (transactionDate, subscriberMSISDN, transactionProfile, currency, transactionAmount, transactionCount) values (?, ?, ?, ?, ?,?) ON DUPLICATE KEY UPDATE transactionCount=transactionCount+VALUES(transactionCount), transactionAmount=transactionAmount+VALUES(transactionAmount)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{ ps, i ->
					ps.setDate(1, toSqlDate(aggregation[i].key.date))
					ps.setString(2, aggregation[i].key.subscriberMSISDN)
					ps.setString(3, aggregation[i].key.transactionProfile)
					ps.setString(4, aggregation[i].key.currency)
					ps.setBigDecimal(5, aggregation[i].transactionAmount)
					ps.setBigDecimal(6, aggregation[i].transactionCount)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}

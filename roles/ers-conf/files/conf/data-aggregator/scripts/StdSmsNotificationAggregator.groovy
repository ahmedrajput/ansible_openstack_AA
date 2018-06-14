package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import StdSmsNotificationAggregator.Key

/**
 * This aggregator will be used to send sms notification per day
 * to the region representatives.
 * This aggregator will run on every 2 minutes.
 *
 * @author Bilal Mirza
 */

@Log4j
@DynamicMixin
public class StdSmsNotificationAggregator extends AbstractAggregator
{
	static final def TABLE = "std_sms_notification_aggregation";
	static final def ALLOWED_PROFILES = [
		"CREDIT_TRANSFER",
		"REVERSE_CREDIT_TRANSFER"
		]
	
	@Value('${StdSmsNotificationAggregator.batch:1000}')
	int limit
	
	@EqualsAndHashCode
	@ToString
	private static class Key implements Comparable<Key>
	{
		Date date
		String region
		String senderId
		
		int compareTo(Key other) {
			date <=> other.date ?: region <=> other.region ?: senderId <=> other.senderId
		}
	}

	@Transactional
	@Scheduled(cron = '${StdSmsNotificationAggregator.cron:0 0/30 * * * ?}')
	public void aggregate()
	{
		def txTransactions
		log.info("\n\nStdSmsNotificationAggregator started feeding transactions\n\n")
		
		use(TimeCategory) {
			txTransactions = getTransactions(new Date() - 1.minute, limit)
		}
		
		if (!txTransactions) {
			log.info("No transactions returned")
			return
		}
		
		def matchedTransactions = txTransactions.findAll {
			it != null && it.resultCode == 0 && ALLOWED_PROFILES.contains(it.getProfile())
		}
		
		if (matchedTransactions)
		{
			def sendertransactions = matchedTransactions.collect
			{
				def date = it.getEndTime().clearTime()
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				def profileId = tr.get("profileId")?.getAsString()
				def senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()
				if (senderPrincipalJsonObject)
				{
					def senderResellerId =  asString(senderPrincipalJsonObject, "resellerId")
					def senderResellerRegion = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("senderRegionName")?.getAsString()
					if (senderResellerRegion == null)
					{
							senderResellerRegion = "NO_REGION"
					}
					def senderAmount = 0
					def senderAmountReverse = 0
					tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect
					{
						def senderAccountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
												
						if(senderResellerId == senderAccountId)
						{
							def amount = new BigDecimal("0")
							amount = getBigDecimalFieldFromAnyField(it, "value", "amount")
							senderAmount = amount.negate()
						}
					}
					[date: date, region: senderResellerRegion, senderId: senderResellerId, transactionAmount: senderAmount]
				}
			}
			.findAll
			{
				it!= null
			}
			.groupBy
			{
				new Key(date:it.date, region:it.region, senderId:it.senderId)
			}
			.collect()
			{
				key, list ->
				[key: key, transactionCount: list.size(), transactionAmount: list.sum {it.transactionAmount ?: 0}]
			}
			updateAggregation(sendertransactions)
		}
		updateCursor(txTransactions)
		schedule(50, TimeUnit.MILLISECONDS)
	}
		
	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.");
		if(aggregation)
		{
			def sql = "INSERT INTO ${TABLE} (transactionDate, region, senderId, transactionCount, transactionAmount) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE transactionCount=transactionCount+VALUES(transactionCount), transactionAmount=transactionAmount+VALUES(transactionAmount)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{ ps, i ->
					ps.setDate(1, toSqlDate(aggregation[i].key.date))
					ps.setString(2, aggregation[i].key.region)
					ps.setString(3, aggregation[i].key.senderId)
					ps.setBigDecimal(4, aggregation[i].transactionCount)
					ps.setBigDecimal(5, aggregation[i].transactionAmount)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
	
}
package se.seamless.ers.components.dataaggregator.aggregator

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
/**
 * Groups reseller transactions by: 
 * <ul>
 * <li>date</li>
 * <li>hour</li>
 * <li>channel</li>
 * <li>reseller level</li>
 * <li>category (monetary or nonmonetary transaction)</li>
 * <li>currency</li>
 * </ul>
 * Aggregates count of successful and failed transactions with coresponding amounts.
 * Used by reports:
 * <ul>
 * <li>STD_BIR_2007.rptdesign - Hourly Usage Statistics for Sales</li>
 * <li>STD_BIR_2012.rptdesign - Hourly Usage Statistics for Support</li>
 * </ul>
 *
 * @author Bartłomiej Wachowski
 * @author Michał Podsiedzik
 */
@Log4j
@DynamicMixin
public class StdHourlyUsageStatisticsAggregator extends AbstractAggregator
{
	static final def TABLE = "std_hourly_usage_statistics_aggregation"
	static final def MONETARY_PROFILES = [
			"TOPUP",
			"REVERSE_TOPUP",
			"CREDIT_TRANSFER",
			"REVERSE_CREDIT_TRANSFER",
			"VOT_PURCHASE",
			"VOS_PURCHASE",
			"VOUCHER_REDEEM",
			"REVERSE_VOT_PURCHASE",
			"REVERSE_VOS_PURCHASE",
			"PRODUCT_RECHARGE",
			"SMS_BUNDLE",
			"COMBO_BUNDLE",
			"IDD_BUNDLE",
			"CRBT",
			"DATA_BUNDLE"
	]

	@Value('${StdHourlyUsageStatisticsAggregator.batch:1000}')
	int limit

	@EqualsAndHashCode
	@ToString
	private static class Key
	{
		Date date
		int hour
		String channel
		String level
		String category
		String currency
	}

	@Transactional
	@Scheduled(cron = '${StdHourlyUsageStatisticsAggregator.cron:0 0/30 * * * ?}')
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
        def tr = parser.parse(it.getJSON()).getAsJsonObject()
        log.debug("Processing transaction: ${it}")
        log.debug("Transaction JSON : ${tr}")

		def date = it.getStartTime().clone().clearTime()
		def hour = it.getStartTime()[Calendar.HOUR_OF_DAY]
		def resultCode = it.resultCode
		def profileId = tr.get("profileId")?.getAsString()

		// TODO remove currency
		def currency = collectCurrency(tr)
		def amount = collectAmount(tr,it.profile)

		def channel = asStringOrDefault(tr, "channel")
		
		if (isReversed(it.profile))
		{
            if(amount) {
                amount = amount.negate()
            }
			channel = channel+" (Reversal)"
		}

		def level = collectLevel(tr)
		
		def category = MONETARY_PROFILES.contains(profileId) ? "MONETARY" : "NONMONETARY"
		
		def retrieveStock=it.getErsTransaction()?.getTransactionProperties()?.get("retrieveStock");

		if (retrieveStock == "true")
		{
			amount = amount.negate()
			channel = channel+" (RetrieveStock)"
			level = tr.get("receiverPrincipal")?.getAsJsonObject()?.get("resellerTypeId")?.getAsString()
			profileId = "RETRIEVE_STOCK"
		}

		[date: date, hour: hour, resultCode: resultCode, amount: amount, currency: currency, channel: channel, level: level, category: category]
	}

	private def key =
	{
		log.debug("Creating key from ${it}")
		new Key(date: it.date, hour: it.hour, channel: it.channel, level: it.level, category: it.category, currency: it.currency)
	}

	private def statistics =
	{	key, list ->
		def amountSuccessful = 0
		def amountFailed = 0
		def successful = 0
		def failed = 0
		list.each
		{
			if (it.resultCode == 0)
			{
				amountSuccessful += it.amount
				successful++
			}
			else
			{
				amountFailed += it.amount
				failed++
			}
		}
		def stats = [key: key, successful: successful, failed: failed, amountSuccessful: amountSuccessful, amountFailed: amountFailed]
		log.debug("Created statistics: ${stats}")
		stats
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{
			def sql = "INSERT INTO ${TABLE} (aggregationDate, hour, channel, level, category, currency, successful, amountSuccessful, failed, amountFailed) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{	ps, i ->
					def row = aggregation[i]
					ps.setDate(1, toSqlDate(row.key.date))
					ps.setInt(2, row.key.hour)
					ps.setString(3, row.key.channel)
					ps.setString(4, row.key.level == null ? "UNKNOWN":row.key.level)
					ps.setString(5, row.key.category)
					ps.setString(6, row.key.currency)
					ps.setInt(7, row.successful)
					ps.setBigDecimal(8, limitBigDecimal(row.amountSuccessful))
					ps.setInt(9, row.failed)
					ps.setBigDecimal(10, limitBigDecimal(row.amountFailed))
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}

    def collectLevel =
            {
                def level = it.get("principal")?.getAsJsonObject()?.get("resellerTypeId")?.getAsString()
                if (!level) {
                    level =
                            it.get("senderPrincipal")?.getAsJsonObject()?.get("resellerTypeId")?.getAsString()
                }

                return level;
            }

}

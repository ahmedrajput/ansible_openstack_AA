package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.util.logging.Log4j

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/** db.accounts.driver
 * Parent reseller or reseller A is a reseller that last send money to reseller A.
 * It aggregates transactions of profile CREDIT_TRANSFER.
 * Sender reseller is a parent child reseller is receiver.
 * 
 * @author Micha Podsiedzik
 */
@Log4j
@DynamicMixin
public class StdAlertAppParentResellerAggregator extends AbstractAggregator
{
	private static final def TABLE = "std_parent_reseller_aggregation"

	@Value('${StdAlertAppParentResellerAggregator.batch:1000}')
	int limit

	@Transactional
	@Scheduled(cron = '${StdAlertAppParentResellerAggregator.cron:0 0/30 * * * ?}')
	public void aggregate()
	{
		def transactions = getTransactions(limit)
		if (transactions)
		{
			def aggregation = findAllowedProfiles(transactions, ["CREDIT_TRANSFER"]).findAll(successfulTransactions).collect(transactionInfo).findAll()
			updateAggregation(aggregation)
			updateCursor(transactions)
			schedule()
		}
	}

	private def transactionInfo =
	{
		def tr = parser.parse(it.getJSON()).getAsJsonObject()
		def parentResellerMSISDN = asString(asJson(tr, "senderPrincipal"), "resellerMSISDN")
		def resellerMSISDN = asString(asJson(tr, "receiverPrincipal"), "resellerMSISDN")

		if(parentResellerMSISDN && resellerMSISDN)
		{
			[resultCode: it.resultCode, parentResellerMSISDN: parentResellerMSISDN, resellerMSISDN: resellerMSISDN]
		}
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{
			def date = toSqlDate(new Date())

			// first remove existing data
			def delete = "delete from ${TABLE} where resellerMSISDN = ?"
			jdbcTemplate.batchUpdate(delete, [
				setValues: { ps, i ->
					ps.setString(1, aggregation[i].resellerMSISDN)
				},
				getBatchSize: { aggregation.size() }
			] as BatchPreparedStatementSetter)

			// then insert new data
			def sql = "insert into ${TABLE} (aggregationTimestamp, parentResellerMSISDN, resellerMSISDN) values (?, ?, ?) ON DUPLICATE KEY UPDATE aggregationTimestamp=VALUES(aggregationTimestamp),parentResellerMSISDN=VALUES(parentResellerMSISDN)"
			jdbcTemplate.batchUpdate(sql, [
				setValues: {ps, i ->
					ps.setDate(1, date)
					ps.setString(2, aggregation[i].parentResellerMSISDN)
					ps.setString(3, aggregation[i].resellerMSISDN)
				},
				getBatchSize: { aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}

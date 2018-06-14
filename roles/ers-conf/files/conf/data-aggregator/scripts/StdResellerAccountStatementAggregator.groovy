package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.util.logging.Log4j

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/**
 * Aggregates transaction rows per reseller and calculates amount of credit, debit transfers with coressponding
 * balace before and after values. 
 * 
 * @author Michał Podsiedzik
 */
@Log4j
@DynamicMixin
public class StdResellerAccountStatementAggregator extends AbstractAggregator
{
	static final def TABLE = "std_reseller_account_statement_aggregation"

	@Value('${StdResellerAccountStatementAggregator.batch}')
	int limit

	@Transactional
	@Scheduled(cron  = '${StdResellerAccountStatementAggregator.cron}')
	public void aggregate()
	{
		def transactions = getTransactions(limit)
		if (transactions)
		{
			def aggregation = transactions
					.findAll(successfulTransactions)
					.collectMany(transactionRows)
					.findAll(resellersHavingBalanceAndAmount)
					.collect(debitAndCreditAmounts)

			updateAggregation(aggregation)
			
			schedule()
		}
		updateCursor(transactions)
	}

	private def resellersHavingBalanceAndAmount =
	{
		log.debug("Filtering row: ${it}")
		it.resellerId && it.resellerTypeId && it.amount != null && it.balanceBefore != null && it.balanceAfter != null
	}

	private def transactionRows =
	{
		def transaction = parser.parse(it.getJSON()).getAsJsonObject()
		def transactionDate = it.getEndTime().clone()
		def isReversal = isReversed(it.profile)

		transaction.get("transactionRows")?.getAsJsonArray()?.iterator().collect
		{
			log.debug("process transaction row: ${it}")

			def accountTypeId = asStringFromField(it, "accountSpecifier","accountTypeId")

			def principal = findPrincipalWithAccount(transaction, it.get("accountSpecifier"))
			def resellerId =  asString(principal, "resellerId")
			def resellerTypeId =  asString(principal, "resellerTypeId")

			// get account details and amount trigger on this account
			def amount = getBigDecimalFieldFromAnyField(it, "value", "amount")
			def balanceBefore = getBigDecimalFieldFromAnyField(it, "value", "balanceBefore")
			def balanceAfter = getBigDecimalFieldFromAnyField(it, "value", "balanceAfter")

			[resellerId: resellerId, resellerTypeId: resellerTypeId, accountTypeId: accountTypeId, amount: amount, balanceBefore: balanceBefore, balanceAfter: balanceAfter, date: transactionDate, isReversal: isReversal]
		}
	}

	private def debitAndCreditAmounts =
	{
		def credit = 0
		def debit = 0

		/**
		 * If amount in transaction is positive in normal case debit account is incremeneted (reseller gets money),
		 * when its reversal credit account should be decremented (money is returned to reseller).
		 * When amount is negative and amount is negative his account is decremeneted (he spends money).
		 */
		if (it.isReversal)
		{
			if (it.amount > 0)
			{
				credit = -it.amount
			}
			else
			{
				debit = it.amount
			}
		}
		else
		{
			if (it.amount > 0)
			{
				credit = it.amount
			}
			else
			{
				debit = -it.amount
			}
		}

		log.debug("Calculated credit = ${credit}, debit = ${debit}")

		[resellerId: it.resellerId, resellerTypeId: it.resellerTypeId, accountTypeId: it.accountTypeId,
			credit: credit, debit: debit, balanceBefore: it.balanceBefore, balanceAfter: it.balanceAfter, date: it.date]
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{
			def sql = "insert into ${TABLE} (aggregationDate, resellerId, resellerTypeId, accountTypeId, credit, debit, balanceBefore, balanceAfter) values (?, ?, ?, ?, ?, ?, ?, ?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{	ps, i ->
					def row = aggregation[i]
					ps.setTimestamp(1, toSqlTimestamp(row.date))
					ps.setString(2, row.resellerId)
					ps.setString(3, row.resellerTypeId)
					ps.setString(4, row.accountTypeId)
					ps.setBigDecimal(5, row.credit)
					ps.setBigDecimal(6, row.debit)
					ps.setBigDecimal(7, row.balanceBefore)
					ps.setBigDecimal(8, row.balanceAfter)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}

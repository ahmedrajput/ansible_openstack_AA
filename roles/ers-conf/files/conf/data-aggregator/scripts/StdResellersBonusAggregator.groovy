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
 * This aggregator will be used to aggregate data from all PROMOTION_TRANSFER transactions in to 'std_resellers_bonus_aggregation' table  
 * @author Bilal Mirza
 */

@Log4j
@DynamicMixin
public class StdResellersBonusAggregator extends AbstractAggregator
{
	static final def TABLE = "std_resellers_bonus_aggregation"
	static final def ALLOWED_PROFILES = ["CREDIT_TRANSFER","REVERSE_CREDIT_TRANSFER"]

	@Value('${StdResellersBonusAggregator.batch:1000}')
	int limit
	
	@Transactional
	@Scheduled(cron = '${StdResellersBonusAggregator.cron:0 0/30 * * * ?}')
	public void aggregate()
	{
		def txTransactions
		log.info("\n\nStdResellersBonusAggregator started feeding transactions\n\n")

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

			def sendertransactions = matchedTransactions
					.findAll(successfulTransactions)
					.collectMany(transactionRows)
					.findAll(validTransactions)
			updateAggregation(sendertransactions)
		}
		updateCursor(txTransactions)
		schedule(50, TimeUnit.MILLISECONDS)
	}

	private def validTransactions =
	{
		it.resellerId && it.resellerId != "OPERATOR"  && it.resellerTypeId && it.resellerTypeId != "promotionproducer" && it.amount != null && it.balanceBefore != null && it.balanceAfter != null 
	}
	

	private def transactionRows =
	{
		def tr = parser.parse(it.getJSON()).getAsJsonObject()
		def transactionDate = it.getEndTime().clone().clearTime()
		def isReversal = isReversed(it.profile)

		tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect
		{
			log.debug("process transaction row: ${it}")
			def accountTypeId = asStringFromField(it, "accountSpecifier","accountTypeId")
			def principal = findPrincipalWithAccount(tr, it.get("accountSpecifier"))
			def channel = asString(tr, "channel")
			def resellerID =  asString(principal, "resellerId")
			def resellerTypeId =  asString(principal, "resellerTypeId")
			def amount = getBigDecimalFieldFromAnyField(it, "value", "amount")
			def currency = getStringFieldFromAnyField(it, "currency", "amount")
			def balanceBefore = getBigDecimalFieldFromAnyField(it, "value", "balanceBefore")
			def balanceAfter = getBigDecimalFieldFromAnyField(it, "value", "balanceAfter")
			log.debug("\n\n***************transaction\n" + tr + "\n***************")	
			
			[resellerId: resellerID, resellerTypeId:resellerTypeId, accountTypeId: accountTypeId, amount: amount, balanceBefore: balanceBefore, balanceAfter: balanceAfter, date: transactionDate, isReversal: isReversal,channel:channel, currency:currency]
		}
	}

	private def updateAggregation(List aggregation)
	{
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation)
		{

			def sql = "INSERT INTO ${TABLE} (dateOfBonusReceived, resellerID, accountType, bonusAmount, availableCredit, totalCreditReceived, currency) values (?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE bonusAmount=bonusAmount+VALUES(bonusAmount), availableCredit=VALUES(availableCredit), totalCreditReceived=totalCreditReceived+VALUES(totalCreditReceived)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues:
				{ ps, i ->
					ps.setDate(1, toSqlDate(aggregation[i].date))
					ps.setString(2, aggregation[i].resellerId)
					ps.setString(3, aggregation[i].accountTypeId)
					ps.setBigDecimal(4, (aggregation[i].channel == "WEB_PROMOTION" ? aggregation[i].amount:0))
					ps.setBigDecimal(5, aggregation[i].balanceAfter)
					ps.setBigDecimal(6, aggregation[i].amount < 0 ? 0 : aggregation[i].amount)
					 ps.setString(7, aggregation[i].currency)
				},
				getBatchSize:
				{ aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}


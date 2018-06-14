package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import java.util.concurrent.TimeUnit
/**
 * Update last transaction for each reseller. Calculate balance and credit given since activation.
 * @author Marek Smigielski
 */
@Log4j
@DynamicMixin
public class StdLastTransactionAggregator extends AbstractAggregator {

	@Value('${StdLastTransactionAggregator.batch:1000}')
	int limit

	@EqualsAndHashCode
	private static class Key {
		String ersReference
		String resellerId
		String resellerMSISDN
		String receiverMSISDN
		String resellerName
		String resellerLevel
		String accountTypeId
		String accountId
	}
	
	@Transactional
	@Scheduled(cron  = '${StdLastTransactionAggregator.cron:0 0/2 * * * ?}')
	public void aggregate() {

		def txTransactions
		use(TimeCategory) {
			txTransactions = getTransactions(new Date() - 1.minute, limit).findAll(successfulTransactions)
		}

		if (!txTransactions) {
			log.info("No transactions returned")
			return
		}

		def aggregation = txTransactions.collectMany {
			def date = it.getEndTime().clone().clearTime()
			def tr = parser.parse(it.getJSON()).getAsJsonObject()
			def resultCode = tr?.get("resultCode")?.getAsInt()
			def retrieveStock=it.getErsTransaction()?.getTransactionProperties()?.get("retrieveStock");

			def transactionType = it.getProfile()
			def transactionAmount = collectAmount(tr,it.profile)
			def ersReference = tr?.get("ersReference")?.getAsString()
			def transactionCurrency = collectCurrency(tr)
			def receiverMSISDN = ""
                                if (tr.get("topupPrincipal")) {
                                        receiverMSISDN = tr.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberMSISDN")?.getAsString()
                                } else if (tr.get("receiverPrincipal")) {
                                        receiverMSISDN = tr.get("receiverPrincipal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString() ?:
                                                tr.get("receiverPrincipal")?.getAsJsonObject()?.get("subscriberMSISDN")?.getAsString()
                                }
			log.debug("***************transaction\n" + tr + "\n***************")
			tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
				log.debug("process transaction row:" + it)
				
				def principal = findPrincipalWithAccount(tr,it.get("accountSpecifier"))
				
				def resellerId =  asString(principal, "resellerId")
				def resellerMSISDN = asString(principal, "resellerMSISDN")
				def resellerName = asString(principal, "resellerName")
				def resellerLevel =  asString(principal, "resellerTypeName")
				
				// get account details and amount trigger on this account
				def accountId = asStringFromField(it, "accountSpecifier","accountId")
				def accountTypeId = asStringFromField(it, "accountSpecifier","accountTypeId")
				def accountAmount = getBigDecimalFieldFromAnyField(it,"value","amount")
				def accountCurrency = getStringFieldFromAnyField(it,"currency", "amount")
[ ersReference : ersReference,resellerMSISDN: resellerMSISDN, resellerId: resellerId, resellerName: resellerName, resellerLevel: resellerLevel,
accountId: accountId, accountTypeId: accountTypeId, accountAmount: accountAmount, accountCurrency: accountCurrency,
		date: date, resultCode: resultCode, transactionType: transactionType ,transactionAmount: transactionAmount, transactionCurrency: transactionCurrency, receiverMSISDN: receiverMSISDN, retrieveStock: retrieveStock]
				
	}
			
		}.findAll {
            log.debug("Forming key : ${it} and it.transactionAmount: ${it.transactionAmount}" )
			it.resellerId != null && it.transactionAmount !=0  && it.retrieveStock!="true"
		}.groupBy {
			new Key(ersReference: it.ersReference, resellerMSISDN: it.resellerMSISDN,receiverMSISDN: it.receiverMSISDN, resellerId: it.resellerId, resellerName: it.resellerName, resellerLevel: it.resellerLevel,
				accountId: it.accountId, accountTypeId: it.accountTypeId)
		}.collect { key, list ->
			//aggregate total credit since activation, account balance, number of transaction,  last transaction
			[key : key, transactionCount: list.size(), balanceChange:  list.sum { it.accountAmount }, newCredit: list.sum { it.accountAmount>0?it.accountAmount:0 }, 
				lastTransactionDate: list.last().date, lastTransactionType: list.last().transactionType, lastTransactionAmount: list.last().transactionAmount, lastTransactionCurrency: list.last().transactionCurrency]
		}
		
		updateAggregation(aggregation)

		updateCursor(txTransactions)

		schedule(50, TimeUnit.MILLISECONDS)
	}
	
	private def updateAggregation = { aggregation ->
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation) {
			def sql = 
			"""INSERT INTO std_last_transaction_aggregation
			(keyHash, resellerId, resellerMSISDN, resellerName, resellerLevel, accountTypeId, accountId,
				transactionCount, balance, totalCredit, lastTransactionDate, lastTransactionType, lastTransactionAmount, lastTransactionCurrency, receiverMSISDN, lastTransactionId)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON DUPLICATE KEY UPDATE 
					transactionCount=transactionCount+VALUES(transactionCount),
					balance=balance+VALUES(balance),
					totalCredit=totalCredit+VALUES(totalCredit),
					lastTransactionDate=VALUES(lastTransactionDate),
					lastTransactionType=VALUES(lastTransactionType),
					lastTransactionAmount=VALUES(lastTransactionAmount),
					lastTransactionCurrency=VALUES(lastTransactionCurrency)"""
			aggregation.each{ row ->
				def batchUpdate = jdbcTemplate.update(sql, [
						setValues: { ps ->
							ps.setInt(1,row.key.hashCode())
							ps.setString(2,row.key.resellerId)
							ps.setString(3, row.key.resellerMSISDN)
							ps.setString(4, row.key.resellerName)
							ps.setString(5, row.key.resellerLevel)
							ps.setString(6, row.key.accountTypeId)
							ps.setString(7, row.key.accountId)
							ps.setInt(8,row.transactionCount)
							ps.setBigDecimal(9,row.balanceChange)
							ps.setBigDecimal(10,row.newCredit)
							ps.setDate(11, new java.sql.Date(row.lastTransactionDate.getTime()))
							ps.setString(12, row.lastTransactionType)
							ps.setBigDecimal(13, row.lastTransactionAmount)
							ps.setString(14, row.lastTransactionCurrency)
							ps.setString(15, row.key.receiverMSISDN)
							ps.setString(16, row.key.ersReference)
						}
				] as PreparedStatementSetter)
			}

		}
	}
}

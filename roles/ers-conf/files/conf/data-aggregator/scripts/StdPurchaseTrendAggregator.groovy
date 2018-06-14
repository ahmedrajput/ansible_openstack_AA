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

/**
 *This aggregator calculates the amount of purchases for every reseller on a particular day.
 *
 * @author Parijat Mukherjee
 */

@Log4j
@DynamicMixin
public class StdPurchaseTrendAggregator extends AbstractAggregator {
	static final def TABLE = "std_purchase_trend_aggregation";
	
	@Value('${StdPurchaseTrendAggregator.allowed_profiles:CREDIT_TRANSFER,REVERSE_CREDIT_TRANSFER,REVERSE_TOPUP,REVERSAL,SUPPORT_TRANSFER,MM2ERS}')
	String allowed_profiles
	
	def allowedProfiles = []
	
	@Value('${StdPurchaseTrendAggregator.batch:100}')
	
	int limit

	@EqualsAndHashCode
	@ToString
	private static class Key implements Comparable<Key> {
		Date date
		String resellerId
		String resellerName
		String resellerMSISDN
		String resellerType
		String resellerParent
		String resellerPath
		int compareTo(Key other) {
			date <=> other.date ?: resellerId <=> other.resellerId ?:
					resellerName <=> other.resellerName ?: resellerMSISDN <=> other.resellerMSISDN ?:
					resellerType <=> other.resellerType ?:
					resellerParent <=> other.resellerParent ?:
					resellerPath <=> other.resellerPath
		}
	}

	@Transactional
	@Scheduled(cron = '${StdPurchaseTrendAggregator.cron}')
	public void aggregate() {
		def txTransactions
		log.info("\n StdPurchaseTrendAggregator started feeding transactions \n\n")

		use(TimeCategory) {
			txTransactions = getTransactions(new Date() - 1.minute, limit)
		}

		if (!txTransactions) {
			log.info("No transactions returned")
			return
		}
		
		allowedProfiles = []
		
		if(allowed_profiles){
			def allowedProfileList = allowed_profiles.split(",")
			for (resellerProfile in allowedProfileList) {
					allowedProfiles.add(resellerProfile.asType(String))
			}
		}

		def matchedTransactions = txTransactions.findAll {
			it != null && it.resultCode == 0 && allowedProfiles.contains(it.getProfile())
		}

		if (matchedTransactions) {
			def sendertransactions = matchedTransactions.collect {
				def date = it.getEndTime().clone().clearTime()
				def tr = parser.parse(it.getJSON()).getAsJsonObject()
				def profileId = tr.get("profileId")?.getAsString()
				def isRetrieveStock=it.getErsTransaction()?.getTransactionProperties()?.get("retrieveStock");
				def receiverPrincipalJsonObject = tr.get("receiverPrincipal")?.getAsJsonObject()
				if(isRetrieveStock=="true"){

					receiverPrincipalJsonObject= tr.get("senderPrincipal")?.getAsJsonObject()
				}
				def productId = tr.get("productId")?.getAsString()
				log.debug("receiverPrincipalJsonObject: ${receiverPrincipalJsonObject}")
				if (receiverPrincipalJsonObject && productId) {
					def resellerId =  asString(receiverPrincipalJsonObject, "resellerId")
					def resellerName = asString(receiverPrincipalJsonObject, "resellerName")
					def resellerMSISDN = asString(receiverPrincipalJsonObject, "resellerMSISDN")
					def resellerType = asString(receiverPrincipalJsonObject, "resellerTypeName")
					def resellerParent = asString(receiverPrincipalJsonObject, "parentResellerId")
					def resellerPath = asString(receiverPrincipalJsonObject, "resellerPath")
					def accountId = ""
					receiverPrincipalJsonObject.get("accounts")?.getAsJsonArray().iterator().collect
					{
					  accountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
					}
					def amount = 0
					tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
						def receiverAccountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()

						if(accountId == receiverAccountId) {
							amount = new BigDecimal("0")
							amount = getBigDecimalFieldFromAnyField(it, "value", "amount")
						}
					}
					if(profileId == "REVERSE_CREDIT_TRANSFER") {
						def receiverAmount = new BigDecimal("0")
						receiverAmount = tr.get("requestedTransferAmount")?.getAsJsonObject()?.get("value")?.getAsBigDecimal()
						amount = new BigDecimal("0")
						amount = receiverAmount.negate()
					}
					if(isRetrieveStock=="true"){
						def receiverAmount = new BigDecimal("0")
						receiverAmount = tr.get("requestedTransferAmount")?.getAsJsonObject()?.get("value")?.getAsBigDecimal()
						amount = new BigDecimal("0")
						amount = receiverAmount.negate()
					}
					if(profileId == "REVERSE_TOPUP") {
						def receiverAmount = new BigDecimal("0")
						amount = new BigDecimal("0")
						receiverAmount = tr.get("requestedTopupAmount")?.getAsJsonObject()?.get("value")?.getAsBigDecimal()
						amount = receiverAmount.negate()
					}
					[
						date: date,
						resellerId: resellerId, 
						resellerName: resellerName,
						resellerType: resellerType,
						resellerMSISDN: resellerMSISDN,
						transactionAmount: amount,
						resellerParent: resellerParent,
						resellerPath : resellerPath
					]
				}
			}
			.findAll { it!= null }
			.groupBy {
				new Key(date:it.date, resellerId:it.resellerId, resellerName: it.resellerName,
				resellerMSISDN: it.resellerMSISDN,
				resellerType: it.resellerType, 
				resellerParent: it.resellerParent,
				resellerPath: it.resellerPath)
			}
			.collect() { key, list ->
				[key: key, transactionAmount: list.sum{it.transactionAmount ?: 0}]
			}
			updateAggregation(sendertransactions)
		}
		updateCursor(txTransactions)
		schedule(50, TimeUnit.MILLISECONDS)
	}

	private def updateAggregation(List aggregation) {
		log.info("Aggregated into ${aggregation.size()} rows.");
		if(aggregation) {
			def sql = """
				INSERT INTO ${TABLE} 
				(aggregationDate, resellerId, resellerName, resellerMSISDN, resellerTypeId, transactionAmount,reseller_parent,reseller_path) 
				VALUES (?, ?, ?, ?, ?, ?, ?, ?) 
				ON DUPLICATE KEY UPDATE 
				transactionAmount=transactionAmount+VALUES(transactionAmount),
				resellerTypeId=VALUES(resellerTypeId),
				resellerMSISDN=VALUES(resellerMSISDN),
				reseller_parent=VALUES(reseller_parent),
				reseller_path=VALUES(reseller_path)
				 """
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues: { ps, i ->
					ps.setDate(1, toSqlDate(aggregation[i].key.date))
					ps.setString(2, aggregation[i].key.resellerId)
					ps.setString(3, aggregation[i].key.resellerName)
					ps.setString(4, aggregation[i].key.resellerMSISDN)
					ps.setString(5, aggregation[i].key.resellerType)
					ps.setBigDecimal(6, aggregation[i].transactionAmount)
					ps.setString(7, aggregation[i].key.resellerParent)
					ps.setString(8, aggregation[i].key.resellerPath)
				},
				getBatchSize: { aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}
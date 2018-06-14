package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import com.seamless.ers.interfaces.platform.clients.transaction.model.ERSTransactionResultCode
import org.springframework.jdbc.core.PreparedStatementSetter
import se.seamless.ers.components.dataaggregator.aggregator.AbstractAggregator
import se.seamless.ers.components.dataaggregator.aggregator.DynamicMixin
import java.util.concurrent.TimeUnit


/**
 *
 * @author Kashif Bashir
 */
@Log4j
@DynamicMixin
public class StdMobileAppResellerTransactionSalesAggregator extends AbstractAggregator {

	static final def TABLE = "std_mobile_reseller_tran_stats_aggregation"

	static final def MONETARY_CATEGORIES = [

			"TOPUP",
			"REVERSE_TOPUP",
			"TRANSFER",
			"CREDIT_TRANSFER",
			"REVERSE_CREDIT_TRANSFER",
			"PRODUCT_RECHARGE",
			"VOUCHER_PURCHASE",
			"VOS_PURCHASE",
			"VOT_PURCHASE",
			"PURCHASE"
	]

	static final def VOUCHER_CATEGORIES = [

			"VOUCHER_PURCHASE",
			"VOS_PURCHASE",
			"VOT_PURCHASE",
			"PURCHASE"
	]

	static final def allowedProfiles = [

			"VOUCHER_PURCHASE",
			"VOS_PURCHASE",
			"VOT_PURCHASE",
			"PURCHASE",
			"TOPUP",
			"TRANSFER",
			"CREDIT_TRANSFER"
	]

	@Value('${StdMobileAppResellerTransactionSalesAggregator.batch:1000}')
	Integer limit = 1000

	@EqualsAndHashCode
	@ToString
	private static class Key {

		Date keyTrxDate
		Date transactionDate
		String senderMSISDN
		String profile
		String senderResellerID
		String receiverResellerID
		String receiverMSISDN
		BigDecimal transactionAmount=BigDecimal.ZERO
		BigDecimal resellerCommission=BigDecimal.ZERO
		BigDecimal resellerBonus=BigDecimal.ZERO
		BigDecimal transactionCount=BigDecimal.ZERO
	}

	@Transactional
	@Scheduled(cron = '${StdMobileAppResellerTransactionSalesAggregator.cron:0 0/30 * * * ?}')
	public void aggregate() {

		def transactions = getTransactions(limit)

		if (transactions) {

			def aggregation = findAllowedProfiles(transactions, allowedProfiles).findAll(successfulTransactions).collect(transactionInfo).findAll();

			updateAggregation(aggregation)
			updateCursor(transactions)
			schedule()
		}
	}


	private def transactionInfo =
			{
				log.debug("Processing transaction StdMobileAppResellerTransactionSalesAggregator: ++");

				def transactionDate, keyTrxDate, senderMSISDN, profile, senderResellerID, receiverResellerID, receiverMSISDN, transactionAmount, resellerCommission, resellerBonus, transactionCount

				def transactionJSON = parser.parse(it.getJSON()).getAsJsonObject()

				senderMSISDN = getSenderMSISDN(transactionJSON)
				senderResellerID = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "senderResellerID")

				receiverMSISDN = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "receiverMSISDN")
				transactionDate = it.getStartTime().clone().clearTime();

				keyTrxDate = it.getStartTime().clone();

				profile = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "profileId")

				def transactionType = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "transactionType")
				def productId = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "productId")
				def resultCode = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "resultCode")
				transactionCount = BigDecimal.ONE;

				def receiverPrincipalJsonObject = transactionJSON.get("receiverPrincipal")?.getAsJsonObject()
				def _senderPrincipalJsonObject  = transactionJSON.get("senderPrincipal")?.getAsJsonObject()

				if (receiverPrincipalJsonObject && "TOPUP" != profile) {

					receiverResellerID = asString(receiverPrincipalJsonObject, "resellerId")
				}

				def ersReference = asString(parser.parse(it.getJSON()).getAsJsonObject(), "ersReference")

				if (_senderPrincipalJsonObject && "TOPUP" == profile) {

					transactionJSON.get("transactionRows")?.getAsJsonArray()?.iterator().collect
							{
								String accountId = it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
								String classifier = it.get("classifier")?.getAsString()

								if (accountId.contains("COMMISSION") && classifier != null && classifier.equalsIgnoreCase("COMMISSION")) {

									resellerCommission = getBigDecimalFieldFromAnyField(it, "value", "amount")

									if(resellerCommission < 0) {
										resellerCommission = Math.abs(resellerCommission);
									}
								}

								if (accountId.contains("BONUS")  && classifier != null &&  classifier.equalsIgnoreCase("BONUS")) {

									resellerBonus = getBigDecimalFieldFromAnyField(it, "value", "amount")

									if(resellerBonus < 0) {
										resellerBonus = Math.abs(resellerBonus);
									}
								}
							}
				}

				if (senderMSISDN == "webadmin") {
					senderMSISDN = "WEB"
				}

				transactionAmount = getBigDecimalFieldFromAnyField(transactionJSON, "value", "receivedAmount", "topupAmount", "amount")
				def isRetrieveStock = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "isRetrieveStock")

				def senderPrincipalJsonObject = ""

				if (isRetrieveStock == "true") {

					profile = "RETRIEVE_STOCK"
					if (transactionJSON.get("topupPrincipal")) {
						receiverMSISDN = transactionJSON.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberMSISDN")?.getAsString()
						receiverResellerID = transactionJSON.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberId")?.getAsString()
					} else if (transactionJSON.get("senderPrincipal")) {
						receiverMSISDN = transactionJSON.get("senderPrincipal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString()
						receiverResellerID = transactionJSON.get("senderPrincipal")?.getAsJsonObject()?.get("resellerId")?.getAsString()
					} else if (transactionJSON.get("targetPrincipal")) {
						receiverPrincipalJsonObject = transactionJSON.get("targetPrincipal")?.getAsJsonObject()
						receiverResellerID = asString(receiverPrincipalJsonObject, "resellerId")
						receiverMSISDN = asString(receiverPrincipalJsonObject, "resellerMSISDN")
					}

					senderPrincipalJsonObject = transactionJSON.get("receiverPrincipal")?.getAsJsonObject()

				} else {

					if (transactionJSON.get("topupPrincipal")) {
						receiverMSISDN = transactionJSON.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberMSISDN")?.getAsString()
						receiverResellerID = transactionJSON.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberId")?.getAsString()
					} else if (transactionJSON.get("receiverPrincipal")) {
						receiverMSISDN = transactionJSON.get("receiverPrincipal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString()
						receiverResellerID = transactionJSON.get("receiverPrincipal")?.getAsJsonObject()?.get("resellerId")?.getAsString()
					} else if (transactionJSON.get("targetPrincipal")) {
						receiverPrincipalJsonObject = transactionJSON.get("targetPrincipal")?.getAsJsonObject()
						receiverResellerID = asString(receiverPrincipalJsonObject, "resellerId")
						receiverMSISDN = asString(receiverPrincipalJsonObject, "resellerMSISDN")
					}

					senderPrincipalJsonObject = transactionJSON.get("senderPrincipal")?.getAsJsonObject()
				}

				if (senderPrincipalJsonObject) {
					senderResellerID = asString(senderPrincipalJsonObject, "resellerId")
				}


				if(VOUCHER_CATEGORIES.contains(profile)) {

					senderPrincipalJsonObject = transactionJSON.get("principal")?.getAsJsonObject();
					senderResellerID = asString(senderPrincipalJsonObject, "resellerId")

					def transactionProperties = transactionJSON.get("transactionProperties")?.getAsJsonObject()

					def map = transactionJSON.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()

					if(map.has("purchaseAmount")) {

						def purchaseAmount = map.get("purchaseAmount")

						if(null == purchaseAmount ||  "" == purchaseAmount || purchaseAmount.asString == "") {

							transactionAmount = BigDecimal.ZERO

						} else {

							transactionAmount = purchaseAmount.asBigDecimal
						}
					}

				}


				if("" != profile && null != profile && (MONETARY_CATEGORIES.contains(profile) || VOUCHER_CATEGORIES.contains(profile))) {

					resultCode = it.resultCode;

					transactionAmount = (transactionAmount != null ? transactionAmount : 0)

					if(resultCode == 0) {

						receiverMSISDN = (receiverMSISDN != null ? receiverMSISDN : "");
						receiverResellerID = (receiverResellerID != null ? receiverResellerID : "");
						resellerCommission = (resellerCommission != null ? resellerCommission : 0);
						resellerBonus = (resellerBonus != null ? resellerBonus : 0);
						transactionCount = 1;

						["transactionDate":transactionDate,
						 "senderMSISDN":senderMSISDN,
						 "profile":profile,
						 "senderResellerID":senderResellerID,
						 "receiverResellerID":receiverResellerID,
						 "receiverMSISDN":receiverMSISDN,
						 "transactionAmount":transactionAmount,
						 "resellerCommission":resellerCommission,
						 "resellerBonus":resellerBonus,
						 "transactionCount":transactionCount,
						 "keyTrxDate":keyTrxDate,
						 "ersReference":ersReference
						]
					}
				}
			}


	def index = 0

	private def updateAggregation(List aggregation) {

		if (aggregation) {

			// then insert new data, , receiverResellerID, receiverMSISDN
			def sql = """INSERT INTO ${TABLE} (id, report_type, transaction_date, msisdn, profile, reseller_id, transaction_amount, transaction_commission, transaction_bonus, transaction_count) VALUES (?,?,?,?,?,?,?,?,?,?)
						
						ON DUPLICATE KEY UPDATE 
						
						transaction_amount = transaction_amount + VALUES(transaction_amount),
						
						transaction_commission = transaction_commission + VALUES(transaction_commission),
						
						transaction_bonus = transaction_bonus + VALUES(transaction_bonus),
						
						transaction_count= transaction_count + VALUES(transaction_count)"""

			def batchUpdate = jdbcTemplate.batchUpdate(sql, [

					setValues :
							{ ps, i ->

								def row = aggregation[i]

								java.sql.Timestamp timestamp = toSqlTimestamp(row.transactionDate);
								java.sql.Timestamp keyTrxDate = toSqlTimestamp(row.keyTrxDate);

								def id = keyTrxDate.time + (++index);
								def reportType = "SALES";

								String senderMSISDN = row.senderMSISDN;
								String senderResellerID = row.senderResellerID;

								senderMSISDN = (senderMSISDN == null || senderMSISDN.equals("")) ? senderResellerID : senderMSISDN;

								log.info("\n\n\n"+reportType+">> Inserting Record ERS-REF "+ (row.ersReference))

								ps.setBigDecimal(1, id)
								ps.setString(2, reportType)
								ps.setTimestamp(3, timestamp.clearTime())
								ps.setString(4, senderMSISDN)
								ps.setString(5, row.profile)
								ps.setString(6, senderResellerID)
								ps.setBigDecimal(7, row.transactionAmount)
								ps.setBigDecimal(8, row.resellerCommission)
								ps.setBigDecimal(9, row.resellerBonus)
								ps.setBigDecimal(10, row.transactionCount)

							},

					getBatchSize: { aggregation.size() }

			] as BatchPreparedStatementSetter)
		}
	}
}

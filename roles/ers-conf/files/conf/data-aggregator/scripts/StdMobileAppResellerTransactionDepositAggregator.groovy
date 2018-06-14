package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/**
 *
 * @author Kashif Bashir
 */
@Log4j
@DynamicMixin
public class StdMobileAppResellerTransactionDepositAggregator extends AbstractAggregator {

	static final def TABLE = "std_mobile_reseller_tran_stats_aggregation"

	static final def CREDIT_TRANSFER = "CREDIT_TRANSFER";

	static final def allowedProfiles = [

			"TRANSFER",
			"CREDIT_TRANSFER"
	]


	@Value('${StdMobileAppResellerTransactionDepositAggregator.batch:1000}')
	Integer limit

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
	@Scheduled(cron = '${StdMobileAppResellerTransactionDepositAggregator.cron:0 5 0 * * ?}')
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
				def transactionDate, keyTrxDate, profile, receiverResellerID, receiverMSISDN, transactionAmount, resellerCommission, resellerBonus, transactionCount

				log.debug("Processing transaction StdMobileAppResellerTransactionDepositAggregator: --");

				def transactionJSON = parser.parse(it.getJSON()).getAsJsonObject()

				receiverMSISDN = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "receiverMSISDN")
				transactionDate = it.getStartTime().clone().clearTime();

				keyTrxDate = it.getStartTime().clone();

				profile = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "profileId")

				def transactionType = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "transactionType")
				def productId = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "productId")


				def receiverPrincipalJsonObject = transactionJSON.get("receiverPrincipal")?.getAsJsonObject()

				if (receiverPrincipalJsonObject) {

					receiverResellerID = asString(receiverPrincipalJsonObject, "resellerId")
					receiverMSISDN = asString(receiverPrincipalJsonObject, "resellerMSISDN")
				}

				transactionAmount = getBigDecimalFieldFromAnyField(transactionJSON, "value", "receivedAmount", "topupAmount", "amount")

				def ersReference = asString(parser.parse(it.getJSON()).getAsJsonObject(), "ersReference");

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


				if("" != profile && null != profile && profile == CREDIT_TRANSFER) {

					def resultCode = it.resultCode;

					transactionAmount = (transactionAmount != null ? transactionAmount : 0)

					if(transactionDate != null && receiverMSISDN != null
							&& profile != null && receiverResellerID != null
							&& transactionAmount && transactionAmount > 0 && resultCode == 0) {

						receiverMSISDN = (receiverMSISDN != null ? receiverMSISDN : "");
						receiverResellerID = (receiverResellerID != null ? receiverResellerID : "");
						resellerCommission = (resellerCommission != null ? resellerCommission : 0);
						resellerBonus = (resellerBonus != null ? resellerBonus : 0);
						transactionCount = 1;

						["transactionDate":transactionDate,
						 "profile":profile,
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

			def sql = """INSERT INTO ${TABLE} (id, report_type, transaction_date, msisdn, profile, reseller_id, transaction_amount, transaction_commission, transaction_bonus, transaction_count) VALUES (?,?,?,?,?,?,?,?,?,?)
						
						ON DUPLICATE KEY UPDATE 
						
						transaction_amount = transaction_amount + VALUES(transaction_amount),
						
						transaction_commission = transaction_commission + VALUES(transaction_commission),
						
						transaction_bonus = transaction_bonus + VALUES(transaction_bonus),
						
						transaction_count=(transaction_count + VALUES(transaction_count))"""

			def batchUpdate = jdbcTemplate.batchUpdate(sql, [

					setValues :
							{ ps, i ->

								def row = aggregation[i]

								java.sql.Timestamp timestamp = toSqlTimestamp(row.transactionDate);
								java.sql.Timestamp keyTrxDate = toSqlTimestamp(row.keyTrxDate);

								def id = keyTrxDate.time + (++index);
								def reportType = "DEPOSIT";

								String receiverMSISDN = row.receiverMSISDN;
								String receiverResellerID = row.receiverResellerID;

								receiverMSISDN = (receiverMSISDN == null || receiverMSISDN.equals("")) ? receiverResellerID : receiverMSISDN;

								log.info("\n\n\n"+reportType+">> Inserting Record ERS-REF "+ (row.ersReference))

								ps.setBigDecimal(1, id)
								ps.setString(2, reportType)
								ps.setTimestamp(3, timestamp.clearTime())
								ps.setString(4, receiverMSISDN)
								ps.setString(5, row.profile)
								ps.setString(6, receiverResellerID)
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
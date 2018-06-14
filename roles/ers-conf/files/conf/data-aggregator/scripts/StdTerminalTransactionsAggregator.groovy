package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.jdbc.core.PreparedStatementSetter

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


@Log4j
@DynamicMixin
public class StdTerminalTransactionsAggregator extends AbstractAggregator {

	static final def TABLE = "std_terminal_transactions_aggregation"

	@Value('${StdTerminalTransactionsAggregator.batch:1000}')
	int limit

	static final def ALLOWED_PROFILES = [
		"TOPUP",
		"CREDIT_TRANSFER",
		"VOUCHER_PURCHASE",
		"PURCHASE",
        "PRODUCT_RECHARGE"
	]

	@EqualsAndHashCode
	@ToString
	private static class Key {
		Date transactionDate
		String transactionType
		String productKey
		String ersReference
		String channel
		String faceValueAmount
		String currencyKey
		String customerMargin
		String transactionCount
		String userId
		String remoteAddress
	}

	@Transactional
	@Scheduled(cron = '${StdTerminalTransactionsAggregator.cron:0 0/30 * * * ?}')
	public void aggregate() {
		def transactions = getTransactions(limit)
		log.info(transactions)
		if (transactions) {
			def aggregation = findAllowedProfiles(transactions, ALLOWED_PROFILES)
					.collect(transactionInfo)
					.findAll()
					.groupBy(key)
					.collect(statistics)

			updateAggregation(aggregation)
			updateCursor(transactions)
			schedule()
		}
	}

	private def transactionInfo = {
		log.debug("Processing transaction: ${it}")
		def tr = parser.parse(it.getJSON()).getAsJsonObject()

		log.debug("TransactionData="+tr)
		def channel = tr.get("channel")?.getAsString()
		def state = tr.get("state")?.getAsString()
		if ("POS" == channel && state == "Completed") 
		{
			def transactionType = tr.get("profileId")?.getAsString()
			def ersReference = tr.get("ersReference")?.getAsString()
			def userId = tr.get("principal")?.getAsJsonObject()?.get("user")?.getAsJsonObject()?.get("userId")?.getAsString()
			def transactionDate = it.getEndTime()
			def faceValueAmount =""
			tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
				log.debug("process transaction row:" + it)
				faceValueAmount = getBigDecimalFieldFromAnyField(it,"value","amount")
			}
			def currencyKey = getStringFieldFromAnyField(tr, "currency", "requestedTransferAmount", "requestedTopupAmount") ?: "N/A"
			def remoteAddress = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("remoteAddress")?.getAsString()

			def productKey =""
			if (tr.get("purchaseReceipt")) 
			{
				tr.get("purchaseReceipt")?.getAsJsonObject()?.get("receiptRows")?.getAsJsonArray()?.iterator().collect {
					log.debug("process purchaseReceipt row:" + it)
					productKey =asString(it,"itemSKU")
					currencyKey = getStringFieldFromAnyField(it,"currency","itemUnitPrice")
				}
			} else if (tr.get("receiverReceipt")) 
			{
				tr.get("receiverReceipt")?.getAsJsonObject()?.get("receiptRows")?.getAsJsonArray()?.iterator().collect {
					log.debug("process receiverReceipt row:" + it)
					productKey =asString(it,"itemSKU")
					currencyKey = getStringFieldFromAnyField(it,"currency","itemUnitPrice")
				}
			} else if (tr.get("topupReceipt")) 
			{
				tr.get("topupReceipt")?.getAsJsonObject()?.get("receiptRows")?.getAsJsonArray()?.iterator().collect {
					log.debug("process topupReceipt row:" + it)
					productKey =asString(it,"itemSKU")
					currencyKey = getStringFieldFromAnyField(it,"currency","itemUnitPrice")
				}
			}
			log.debug("Filtered Values[transactionType="+transactionType+", productKey="+productKey+", ersReference="+ersReference+", channel="+channel+", userId="+userId+", faceValueAmount="+faceValueAmount+", currencyKey="+currencyKey+",remoteAddress="+remoteAddress+"]")
			[transactionDate: transactionDate,transactionType:transactionType,productKey:productKey,ersReference:ersReference,channel:channel,userId:userId,faceValueAmount:faceValueAmount,currencyKey:currencyKey,remoteAddress:remoteAddress]
		}
	}

	private key = {
		log.debug("Creating key from ${it}")
		new Key(transactionDate:it.transactionDate, transactionType: it.transactionType, productKey: it.productKey, ersReference: it.ersReference, channel: it.channel,faceValueAmount:it.faceValueAmount,currencyKey:it.currencyKey,userId:it.userId,remoteAddress:it.remoteAddress)
	}

	private statistics = {	key, list ->
		[key:key, quantity:list.size(), amount:list.sum { it.amount ?: 0 }]
	}

	private def updateAggregation(List aggregation) {
		log.info("Aggregated into ${aggregation.size()} rows.")
		if(aggregation) {
			def sql = "INSERT INTO ${TABLE} (transactionDate, transactionType,ersReference,channel,faceValueAmount,currencyKey,userId,remoteAddress,productKey) VALUES (?,?, ?, ?, ?, ?, ?,?,?)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
				setValues: { ps, i ->
					ps.setTimestamp(1, toSqlTimestamp(aggregation[i].key.transactionDate))
					ps.setString(2, aggregation[i].key.transactionType)
					ps.setString(3, aggregation[i].key.ersReference)
					ps.setString(4, aggregation[i].key.channel)
					ps.setString(5, aggregation[i].key.faceValueAmount)
					ps.setString(6, aggregation[i].key.currencyKey)
					ps.setString(7, aggregation[i].key.userId)
					ps.setString(8, aggregation[i].key.remoteAddress)
					ps.setString(9, aggregation[i].key.productKey)
				},
				getBatchSize: { aggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}

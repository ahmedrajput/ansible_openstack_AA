package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.ColumnMapRowMapper

/**
 * Aggregates Voucher Purchase transactions
 * @author M Asad Ullah Khan
 * @author Parijat Mukherjee (added changes for TS-3529)
 */
@Log4j
@DynamicMixin
public class StdEVoucherSaleSummaryAggregator extends AbstractAggregator {

    static final def TABLE = "dataaggregator.std_evoucher_sale_summary_aggregation"

    @Autowired
    @Qualifier("refill")
    private JdbcTemplate refill
    @Value('${StdEVoucherSaleSummaryAggregator.batch:1000}')
    int limit
    static final def ALLOWED_PROFILES = [

            "VOUCHER_PURCHASE",
            "VOS_PURCHASE",
            "VOT_PURCHASE",
            "PURCHASE"
    ]

    @EqualsAndHashCode
    @ToString
    private static class Key {
        Date transactionDate
        String productKey
        String ersReference
        String faceValueAmount
        String currencyKey
        String resellerid
        String resellerMSISDN
        String channel
    }

    @Transactional
    @Scheduled(cron = '${StdEVoucherSaleSummaryAggregator.cron:0 0/30 * * * ?}')
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
        def state = tr.get("state")?.getAsString()
        if (state == "Completed")
        {
            def ersReference = tr.get("ersReference")?.getAsString()
            def transactionDate = it.getEndTime()
            def resellerid = tr.get("principal")?.getAsJsonObject()?.get("resellerId")?.getAsString()
            def resellerMSISDN = tr.get("principal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString()
            def faceValueAmount =""
            tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
                log.debug("process transaction row:" + it)
                faceValueAmount = getBigDecimalFieldFromAnyField(it,"value","amount")
            }
            def voucher_quantity = getQuantity(tr)
            def currencyKey = getCurrency(tr)
            def productKey =  getProductName(tr) ?: getProductId(tr) ?: "UNKOWN"
            if (tr.get("purchaseReceipt"))
            {
                tr.get("purchaseReceipt")?.getAsJsonObject()?.get("receiptRows")?.getAsJsonArray()?.iterator().collect {
                    log.debug("process purchaseReceipt row:" + it)
                    currencyKey = getStringFieldFromAnyField(it,"currency","itemUnitPrice")
                }
            } else if (tr.get("receiverReceipt"))
            {
                tr.get("receiverReceipt")?.getAsJsonObject()?.get("receiptRows")?.getAsJsonArray()?.iterator().collect {
                    log.debug("process receiverReceipt row:" + it)
                    currencyKey = getStringFieldFromAnyField(it,"currency","itemUnitPrice")
                }
            } else if (tr.get("topupReceipt"))
            {
                tr.get("topupReceipt")?.getAsJsonObject()?.get("receiptRows")?.getAsJsonArray()?.iterator().collect {
                    log.debug("process topupReceipt row:" + it)
                    currencyKey = getStringFieldFromAnyField(it,"currency","itemUnitPrice")
                }
            }
            
            def channel = asString(tr, "channel")
            
            log.debug("Filtered Values[productKey="+productKey+", ersReference="+ersReference+",faceValueAmount="+faceValueAmount+", currencyKey="+currencyKey+",resellerid="+resellerid+",voucher_quantity="+voucher_quantity+"channel="+channel+"]")
            [transactionDate: transactionDate,productKey:productKey,ersReference:ersReference,faceValueAmount:faceValueAmount,currencyKey:currencyKey,resellerid:resellerid,resellerMSISDN:resellerMSISDN,voucher_quantity:voucher_quantity,channel:channel]
        }
    }

    private key = {
        log.debug("Creating key from ${it}")
        new Key(transactionDate:it.transactionDate,productKey: it.productKey, ersReference: it.ersReference,faceValueAmount:it.faceValueAmount,currencyKey:it.currencyKey,resellerid:it.resellerid,resellerMSISDN:it.resellerMSISDN, channel:it.channel)
    }

    private statistics = {	key, list ->
        [key:key, quantity:list.size(), totalVoucherPurchased: list.sum { it.voucher_quantity.toInteger() ?: 0 }, amount:list.sum { it.amount ?: 0 }]
    }

    private def updateAggregation(List aggregation) {
        log.info("Aggregated into ${aggregation.size()} rows.")
        if(aggregation) {

            def sql = "INSERT INTO ${TABLE} (product_id,reseller_id,reseller_msisdn,amount,quantity,currency,sell_date,zone,user_group,sub_group,channel) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?) ON DUPLICATE KEY UPDATE quantity = quantity+VALUES(quantity),amount=amount+VALUES(amount),zone=VALUES(zone),user_group=VALUES(user_group),sub_group=VALUES(sub_group)"
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues: { ps, i ->
                        def amount = aggregation[i].key.faceValueAmount.toDouble()

                        ps.setTimestamp(7, toSqlTimestamp(aggregation[i].key.transactionDate))
                        ps.setInt(5, aggregation[i].totalVoucherPurchased)
                        ps.setBigDecimal(4, amount)
                        ps.setString(6, aggregation[i].key.currencyKey)
                        ps.setString(3, aggregation[i].key.resellerMSISDN)
                        ps.setString(2, aggregation[i].key.resellerid)
                        ps.setString(1, aggregation[i].key.productKey)

                        def RESELLER_INFO_SQL ="""
					SELECT rgroup AS 'zone',subrgroup as 'group',subsubrgroup AS 'sub_group' FROM Refill.commission_receivers resellers
					where resellers.chain_store_id = ?
			 		"""
                        def product_status_updates = refill.query(RESELLER_INFO_SQL,  [setValues: { pr ->
                            use(TimeCategory) {
                                pr.setString(1, aggregation[i].key.resellerid.toString())

                            }
                        }] as PreparedStatementSetter, new ColumnMapRowMapper())

                        ps.setString(8, product_status_updates[0]?.Zone?.toString())
                        ps.setString(9, product_status_updates[0]?.group?.toString())
                        ps.setString(10, product_status_updates[0]?.sub_group?.toString())
                        
                        ps.setString(11, aggregation[i].key.channel)
                    },
                    getBatchSize: { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }



    def getProductName =
            {	tr ->
                tr.get("purchasedProducts")?.getAsJsonArray()?.iterator().collect
                {

                    it.get("product")?.getAsJsonObject()?.get("name")?.getAsString()

                }
                .find {it}
            }


    def getQuantity =
            {   tr ->
                tr.get("purchaseReceipt")?.get("receiptRows")?.getAsJsonArray()?.iterator().collect
                {

                    it.get("itemQuantity").getAsString()

                }
                .find {it}
            }


    def getProductId =
            {
                //successful VOT, VOS
                def productID = it.get("resultProperties")?.getAsJsonObject()?.
                        get("map")?.getAsJsonObject()?.get("productSKU")?.getAsString()

                if(productID) {
                    return productID
                }
                return  null;

            }

    def getCurrency =
            {
                def currencyKey = it.get("transactionRows")?.getAsJsonArray()?.get(1)?.getAsJsonObject()?.
                        get("amount")?.getAsJsonObject()?.
                        get("currency")?.getAsString()

                if(currencyKey) {
                    return  currencyKey
                }
                currencyKey = getStringFieldFromAnyField(it, "currency", "requestedTransferAmount", "requestedTopupAmount") ?: "N/A"
                if(currencyKey){
                    return  currencyKey
                }

                return null;

            }
}
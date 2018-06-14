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

import com.seamless.ers.interfaces.platform.clients.transaction.model.ERSTransactionResultCode


/**
 *
 * @author Samimul Alam
 */
@Log4j
@DynamicMixin
public class StdDailyTransactionSummaryAggregator extends AbstractAggregator {
    static final def TABLE = "std_daily_transaction_summary_aggregation"

    static final def MONETARY_CATEGORIES = [
            "TOPUP",
            "REVERSE_TOPUP",
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

    @Value('${StdDailyTransactionSummaryAggregator.batch:1000}')
    int limit

    @Value('${StdDailyTransactionSummaryAggregator.negateAmount:true}')
    boolean negateAmount

    @EqualsAndHashCode
    @ToString
    private static class Key {
        String ersReference
        Date date
        int transactionHour
        String category
        String channel
        String profile
        int resultCode
        String resultStatus
        BigDecimal transactionAmount
        String transactionCurrency
        String receiverMSISDN
        String receiverResellerID
        String senderMSISDN
        String senderResellerID
        BigDecimal senderBalanceBefore
        BigDecimal senderBalanceAfter
        String dealerSequenceNo
        BigDecimal receiverBalanceBefore
        BigDecimal receiverBalanceAfter
        String receiverName
        String senderName
    }

    @Transactional
    @Scheduled(cron = '${StdDailyTransactionSummaryAggregator.cron:0 0/30 * * * ?}')
    public void aggregate() {
        def transactions = getTransactions(limit)
        if (transactions) {
            def aggregation = transactions.collect(transactionInfo)
                    .findAll(transactionFind)
                    .groupBy(key)
                    .collect(statistics)

            updateAggregation(aggregation)
            updateCursor(transactions)
            schedule()
        }
    }

    private def transactionInfo =
            {
                log.debug("Processing transaction StdDailyTransactionSummaryAggregator: ${it}")
                def tr = parser.parse(it.getJSON()).getAsJsonObject()
                log.debug("***************transaction StdDailyTransactionSummaryAggregator :" + tr + " **************")
                def channel = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "channel")
                def productId = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "productId")
                def profile = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "profileId")
                def isRetrieveStock = it.getErsTransaction()?.getTransactionProperties()?.get("retrieveStock");
                def dealerSequenceNo = it.getErsTransaction()?.getTransactionProperties()?.get("dealerSequenceNo");

                def clientReference = it.getErsTransaction()?.getTransactionProperties()?.get("clientReference");


                def receiverBalanceBefore = 0
                def receiverBalanceAfter = 0

                def receiverResellerId = ""
                def receiverResellerName = ""
                def receiverPrincipalJsonObject = tr.get("receiverPrincipal")?.getAsJsonObject()
                if (receiverPrincipalJsonObject && "TOPUP" != profile) {

                    receiverResellerId = asString(receiverPrincipalJsonObject, "resellerId")
                    receiverBalanceBefore = new BigDecimal("0")
                    receiverBalanceAfter = new BigDecimal("0")
                    receiverResellerName = asString(receiverPrincipalJsonObject, "resellerName")

                    tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect
                            {

                                def receiveraccountId = it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
                                if (receiverResellerId == receiveraccountId) {
                                    receiverBalanceBefore = getBigDecimalFieldFromAnyField(it, "value", "balanceBefore")
                                    receiverBalanceAfter = getBigDecimalFieldFromAnyField(it, "value", "balanceAfter")
                                }


                            }
                }

                if (dealerSequenceNo == null) {
                    dealerSequenceNo = clientReference;
                    //log.info("***************transaction StdDailyTransactionSummaryAggregator clientReference :" + dealerSequenceNo + " **************")
                }

                def resultStatus = ""
                if (it.resultCode == 0) {
                    resultStatus = "Success"
                } else {
                    resultStatus = "Failure"
                }

                if (channel == "webadmin") {
                    channel = "WEB"
                }



                def category = MONETARY_CATEGORIES.contains(it.profile) ? "MONETARY" : "NONMONETARY"
                def transactionAmount = getBigDecimalFieldFromAnyField(tr, "value", "receivedAmount", "topupAmount")
                def transactionCurrency = getStringFieldFromAnyField(tr, "currency", "receivedAmount", "topupAmount")
                def receiverMSISDN = ""
                def receiverResellerID = ""
                def senderPrincipalJsonObject = ""
                receiverPrincipalJsonObject = ""
                if (profile == "REVERSE_CREDIT_TRANSFER" || profile == "REVERSE_TOPUP") {
                    if (transactionAmount && transactionAmount < 0 && negateAmount) {
                        transactionAmount = transactionAmount.negate()
                    }
                }
                profile = MONETARY_CATEGORIES.contains(it.profile) ? it.profile : "OTHER"
                if (isRetrieveStock == "true") {
                    profile = "RETRIEVE_STOCK"
                    if (tr.get("topupPrincipal")) {
                        receiverMSISDN = tr.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberMSISDN")?.getAsString()
                        receiverResellerID = tr.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberId")?.getAsString()
                    } else if (tr.get("senderPrincipal")) {
                        receiverMSISDN = tr.get("senderPrincipal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString()
                        receiverResellerID = tr.get("senderPrincipal")?.getAsJsonObject()?.get("resellerId")?.getAsString()
                    } else if (tr.get("targetPrincipal")) {
                        receiverPrincipalJsonObject = tr.get("targetPrincipal")?.getAsJsonObject()
                        receiverResellerID = asString(receiverPrincipalJsonObject, "resellerId")
                        receiverMSISDN = asString(receiverPrincipalJsonObject, "resellerMSISDN")
                    }

                    senderPrincipalJsonObject = tr.get("receiverPrincipal")?.getAsJsonObject()
                } else {
                    if (tr.get("topupPrincipal")) {
                        receiverMSISDN = tr.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberMSISDN")?.getAsString()
                        receiverResellerID = tr.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberId")?.getAsString()
                    } else if (tr.get("receiverPrincipal")) {
                        receiverMSISDN = tr.get("receiverPrincipal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString()
                        receiverResellerID = tr.get("receiverPrincipal")?.getAsJsonObject()?.get("resellerId")?.getAsString()
                    } else if (tr.get("targetPrincipal")) {
                        receiverPrincipalJsonObject = tr.get("targetPrincipal")?.getAsJsonObject()
                        receiverResellerID = asString(receiverPrincipalJsonObject, "resellerId")
                        receiverMSISDN = asString(receiverPrincipalJsonObject, "resellerMSISDN")
                    }

                    senderPrincipalJsonObject = tr.get("senderPrincipal")?.getAsJsonObject()
                }

                def senderResellerID = ""
                def senderResellerTypeId = ""
                def senderResellerName = ""
                def senderMSISDN = ""
                def senderamount = new BigDecimal("0")
                def senderBalanceBefore = new BigDecimal("0")
                def senderBalanceAfter = new BigDecimal("0")
                if (senderPrincipalJsonObject) {
                    senderResellerID = asString(senderPrincipalJsonObject, "resellerId")
                    senderResellerTypeId = asString(senderPrincipalJsonObject, "resellerTypeName")
                    senderResellerName = asString(senderPrincipalJsonObject, "resellerName")
                    senderMSISDN = asString(senderPrincipalJsonObject, "resellerMSISDN")


                    def transactionProperties = tr.get("transactionProperties")?.getAsJsonObject()

                    tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect
                            {

                                def senderAccountId = it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
                                if (senderResellerID == senderAccountId) {
                                    senderamount = getBigDecimalFieldFromAnyField(it, "value", "amount")
                                    senderBalanceBefore = getBigDecimalFieldFromAnyField(it, "value", "balanceBefore")
                                    senderBalanceAfter = getBigDecimalFieldFromAnyField(it, "value", "balanceAfter")
                                }

                            }
                } else if (tr.get("principal")) {
                    senderPrincipalJsonObject = tr.get("principal")?.getAsJsonObject()
                    senderResellerID = asString(senderPrincipalJsonObject, "resellerId")
                    senderMSISDN = asString(senderPrincipalJsonObject, "resellerMSISDN")
                    if(VOUCHER_CATEGORIES.contains(profile)) {
                        tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect
                                {

                                    def senderAccountId = it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
                                    if (senderResellerID == senderAccountId) {
                                        transactionAmount = getBigDecimalFieldFromAnyField(it, "value", "amount")
                                        senderBalanceBefore = getBigDecimalFieldFromAnyField(it, "value", "balanceBefore")
                                        senderBalanceAfter = getBigDecimalFieldFromAnyField(it, "value", "balanceAfter")
                                        transactionAmount = transactionAmount.negate()
                                    }

                                }
                    }
                }

                //To insert a transaction value into db,
                // 1. add element to the below map.
                // 2. Modify inner Key class.
                // 3. update (def key) definition to include this variable in Key's constructor.
                // 4. Modify updateAggregation -> sql & preparedStatement accordingly.
                [date                : it.getStartTime(), transactionHour: it.getStartTime()[Calendar.HOUR_OF_DAY], profile: profile, resultCode: it.resultCode, resultStatus: resultStatus, channel: channel,
                 category            : category, transactionAmount: transactionAmount, transactionCurrency: transactionCurrency,
                 receiverMSISDN      : receiverMSISDN, receiverResellerID: receiverResellerID, senderResellerID: senderResellerID, senderMSISDN: senderMSISDN, receiverBalanceBefore: receiverBalanceBefore,
                 receiverBalanceAfter: receiverBalanceAfter, senderBalanceBefore: senderBalanceBefore, senderBalanceAfter: senderBalanceAfter, ersReference: it.ersReference, dealerSequenceNo: dealerSequenceNo,
                 receiverName        : receiverResellerName, senderName: senderResellerName]
            }
//Removed receiver null check to also incorporate different voucher based transaction
    private def transactionFind =
            {
                it.senderResellerID != null && it.senderResellerID != "" 
            }

    private def key =
            {

                new Key(date: it.date, transactionHour: it.transactionHour, category: it.category, channel: it.channel,
                        profile: it.profile, resultCode: it.resultCode, resultStatus: it.resultStatus,
                        transactionAmount: it.transactionAmount, transactionCurrency: it.transactionCurrency,
                        receiverMSISDN: it.receiverMSISDN, receiverResellerID: it.receiverResellerID,
                        senderResellerID: it.senderResellerID, senderMSISDN: it.senderMSISDN,
                        senderBalanceBefore: it.senderBalanceBefore, senderBalanceAfter: it.senderBalanceAfter,
                        receiverBalanceAfter: it.receiverBalanceAfter, receiverBalanceBefore: it.receiverBalanceBefore,
                        ersReference: it.ersReference, dealerSequenceNo: it.dealerSequenceNo, receiverName: it.receiverName, senderName: it.senderName)
            }

    private def statistics =
            { key, list ->
                [key: key, total: list.size()]
            }

    private def updateAggregation(List aggregation) {
        log.info("Aggregated into StdDailyTransactionSummaryAggregator ${aggregation.size()} rows.")
        if (aggregation) {

            def date = new Date()
            use(TimeCategory)
                    {
                        date = toSqlTimestamp(date - 15.days)
                    }

            // first remove old data
            def delete = "delete from ${TABLE} where transactionDate < ?"
            jdbcTemplate.batchUpdate(delete, [
                    setValues   : { ps, i ->
                        ps.setTimestamp(1, toSqlTimestamp(date))
                    },
                    getBatchSize: { aggregation.size() }
            ] as BatchPreparedStatementSetter)

            // then insert new data
            def sql = "INSERT INTO ${TABLE} (transactionDate, transactionHour, transactionReference, senderMSISDN, senderResellerID, receiverMSISDN, receiverResellerID, transactionType, amount, channel, resultStatus, externalID, senderBalanceBefore, senderBalanceAfter, currency, resultDescription, receiverBalanceBefore, receiverBalanceAfter, senderResellerName, receiverResellerName) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues   :
                            { ps, i ->
                                def row = aggregation[i]

                                ps.setTimestamp(1, toSqlTimestamp(row.key.date))
                                ps.setInt(2, row.key.transactionHour)
                                ps.setString(3, row.key.ersReference)
                                ps.setString(4, row.key.senderMSISDN)
                                ps.setString(5, row.key.senderResellerID)
                                ps.setString(6, row.key.receiverMSISDN)
                                ps.setString(7, row.key.receiverResellerID)
                                ps.setString(8, row.key.profile)
                                ps.setBigDecimal(9, row.key.transactionAmount)
                                ps.setString(10, row.key.channel)
                                ps.setString(11, row.key.resultStatus)
                                ps.setString(12, row.key.dealerSequenceNo)
                                ps.setBigDecimal(13, limitBigDecimal(row.key.senderBalanceBefore))
                                ps.setBigDecimal(14, limitBigDecimal(row.key.senderBalanceAfter))
                                ps.setString(15, row.key.transactionCurrency)
                                ps.setString(16, ERSTransactionResultCode.lookupResultCode(row.key.resultCode))
                                ps.setBigDecimal(17, limitBigDecimal(row.key.receiverBalanceBefore))
                                ps.setBigDecimal(18, limitBigDecimal(row.key.receiverBalanceAfter))
                                ps.setString(19, row.key.senderName)
                                ps.setString(20, row.key.receiverName)
                            },
                    getBatchSize:
                            { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}

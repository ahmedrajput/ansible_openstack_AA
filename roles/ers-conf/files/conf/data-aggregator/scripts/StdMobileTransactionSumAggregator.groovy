package se.seamless.ers.components.dataaggregator.aggregator

import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import se.seamless.ers.components.dataaggregator.aggregator.AbstractAggregator
import se.seamless.ers.components.dataaggregator.aggregator.DynamicMixin
import java.util.Map
import java.util.Calendar
import java.util.GregorianCalendar
import org.springframework.beans.factory.annotation.Value;
import java.util.Date;
import javax.annotation.PostConstruct;

/**
 *
 * @author Kashif Bashir
 */
@Log4j
@DynamicMixin
public class StdMobileTransactionSumAggregator extends AbstractAggregator {

    static final def TABLE = "std_mobile_transactions_sum_aggregation";
    static final def TOPUP = "TOPUP";
    static final def PRODUCT_RECHARGE = "PRODUCT_RECHARGE";
    static final def CREDIT_TRANSFER = "CREDIT_TRANSFER";
    static final def TRANSFER = "TRANSFER";

    static final def MONETARY_CATEGORIES = [

            "TOPUP",
            "TRANSFER",
            "CREDIT_TRANSFER",
            "PRODUCT_RECHARGE"
//			"REVERSE_TOPUP", "REVERSE_CREDIT_TRANSFER",
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


    @Value('${StdMobileTransactionSumAggregator.batch:1000}')
    Integer limit = 1000;
    private Date date = null;
    private Map<String,Object> resultMap = null;

    @Transactional
    @Scheduled(cron = '${StdMobileTransactionSumAggregator.cron:0 0/30 * * * ?}')
    public void aggregate() {

        Calendar calendar = new GregorianCalendar();
        calendar.set(calendar.HOUR, 0);
        calendar.set(calendar.MINUTE, 0);
        calendar.set(calendar.SECOND, 0);
        calendar.set(Calendar.AM_PM, Calendar.AM);
        date = calendar.getTime();

        this.loadResultMap();

        def transactions = getTransactions(limit)

        if (transactions) {

            def aggregation = findAllowedProfiles(transactions, allowedProfiles).findAll(successfulTransactions).collect(transactionInfo).findAll()

            updateAggregation(aggregation)
            updateCursor(transactions)
            schedule()
        }
    }


    def currentDate = null;
    def trxCountTillDate = 0L;
    def trxAmountTillDate = 0D;
    def trxTopUpCountTillDate = 0L;
    def trxTopUpAmountTillDate = 0D;
    def trxTransferCountTillDate = 0L;
    def trxTransferAmountTillDate = 0D;
    def trxVoucherCountTillDate = 0L;
    def trxVoucherAmountTillDate = 0D;


//    @PostConstruct
    private void loadResultMap() {

        try {

            if(resultMap != null) {

                this.resultMap.clear();
            }

            this.resultMap = jdbcTemplate.queryForMap("SELECT * FROM " + TABLE + " ORDER BY DATE_UPDATED DESC LIMIT 1");

        } catch (org.springframework.dao.EmptyResultDataAccessException e) {

            this.resultMap = new HashMap<String,Object>(0);
            this.resultMap.put("TOTAL_TRANSACTIONS_LATEST_COUNT", Long.valueOf(0L));
            this.resultMap.put("TOTAL_TRANSACTIONS_LATEST_AMOUNT", Double.valueOf(0.0D));
            this.resultMap.put("TOTAL_TOPUP_LATEST_COUNT", Long.valueOf(0L));
            this.resultMap.put("TOTAL_TOPUP_LATEST_AMOUNT", Double.valueOf(0.0D));
            this.resultMap.put("TOTAL_TRANSFER_LATEST_COUNT", Long.valueOf(0L));
            this.resultMap.put("TOTAL_TRANSFER_LATEST_AMOUNT", Double.valueOf(0.0D));
            this.resultMap.put("TOTAL_VOUCHER_LATEST_COUNT", Long.valueOf(0L));
            this.resultMap.put("TOTAL_VOUCHER_LATEST_AMOUNT", Double.valueOf(0.0D));
            this.resultMap.put("DATE_UPDATED", new Date());

        } finally {

            trxCountTillDate = (Long) resultMap.get("TOTAL_TRANSACTIONS_LATEST_COUNT");
            trxAmountTillDate = (Double) resultMap.get("TOTAL_TRANSACTIONS_LATEST_AMOUNT");
            trxTopUpCountTillDate = (Long) resultMap.get("TOTAL_TOPUP_LATEST_COUNT");
            trxTopUpAmountTillDate = (Double) resultMap.get("TOTAL_TOPUP_LATEST_AMOUNT");
            trxTransferCountTillDate = (Long) resultMap.get("TOTAL_TRANSFER_LATEST_COUNT");
            trxTransferAmountTillDate = (Double) resultMap.get("TOTAL_TRANSFER_LATEST_AMOUNT");
            trxVoucherCountTillDate = (Long) resultMap.get("TOTAL_VOUCHER_LATEST_COUNT")
            trxVoucherAmountTillDate = (Double) resultMap.get("TOTAL_VOUCHER_LATEST_AMOUNT");
            currentDate = (Date) resultMap.get("DATE_UPDATED");
        }

//        log.info("\nloadResultMap() " + date + " resultMap "+resultMap);
    }

    private def transactionInfo =
            {

                def transactionJSON = parser.parse(it.getJSON()).getAsJsonObject()
                def transactionDate = it.getStartTime().clone().clearTime();
                def profile = asStringOrDefault(parser.parse(it.getJSON()).getAsJsonObject(), "profileId")
                def transactionStatus = successfulTransactions(it) ? "SUCCESS" : "FAILED";
                def amount = 0;

                if ("" != profile && null != profile && it.resultCode == 0 &&
                        (VOUCHER_CATEGORIES.contains(profile) || MONETARY_CATEGORIES.contains(profile)) &&
                        transactionStatus.equalsIgnoreCase("SUCCESS")) {

                    Long totalTransactionCount = 0;
                    Double totalTransactionAmount = 0D;
                    Long topupTransactionCount = 0;
                    Double topupTransactionAmount = 0D;
                    Long transferTransactionCount = 0L;
                    Double transferTransactionAmount = 0D
                    Long voucherTransactionCount = 0L;
                    Double voucherTransactionAmount = 0D;

                    if(currentDate != null && currentDate.compareTo(transactionDate) != 0)
                    {
                        totalTransactionCount = trxCountTillDate;
                        totalTransactionAmount = trxAmountTillDate;
                        topupTransactionCount = trxTopUpCountTillDate;
                        topupTransactionAmount = trxTopUpAmountTillDate;
                        transferTransactionCount = trxTransferCountTillDate;
                        transferTransactionAmount = trxTransferAmountTillDate;
                        voucherTransactionCount = trxVoucherCountTillDate;
                        voucherTransactionAmount = trxVoucherAmountTillDate;
                    }

                    Long topupTransactionCountToday = 0;
                    Double topupTransactionAmountToday = 0;
                    Long transferTransactionCountToday = 0;
                    Double transferTransactionAmountToday = 0;
                    Long voucherTransactionCountToday = 0;
                    Double voucherTransactionAmountToday = 0;


                    if (VOUCHER_CATEGORIES.contains(profile)) {

                        senderPrincipalJsonObject = transactionJSON.get("principal")?.getAsJsonObject();

                        def map = transactionJSON.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()

                        if (map.has("purchaseAmount")) {

                            amount = map.get("purchaseAmount");

                            if (null == voucherAmount || "" == voucherAmount || voucherAmount.asString == "") {

                                amount = BigDecimal.ZERO

                            } else {

                                amount = voucherAmount.asBigDecimal
                            }

                            VOUCHER_TX:
                            {
                                voucherTransactionCountToday = 1;
                                voucherTransactionAmountToday = amount;

                                voucherTransactionCount += 1;
                                voucherTransactionCount += amount;

                                trxVoucherCountTillDate += 1;
                                trxVoucherAmountTillDate += amount;
                            }
                        }
                    }


                    if (MONETARY_CATEGORIES.contains(profile)) {

                        amount = getBigDecimalFieldFromAnyField(transactionJSON, "value", "receivedAmount", "topupAmount");

                        if (profile.equals(TOPUP) || profile.equals(PRODUCT_RECHARGE)) {

                            TOPUP_TX:
                            {
                                topupTransactionCountToday = 1;
                                topupTransactionAmountToday = amount;

                                topupTransactionCount += 1;
                                topupTransactionAmount += amount;

                                trxTopUpCountTillDate += 1;
                                trxTopUpAmountTillDate += amount;
                            }
                        }

                        if (profile.equals(TRANSFER) || profile.equals(CREDIT_TRANSFER)) {

                            TRANSFER_TX:
                            {
                                transferTransactionCountToday = 1;
                                transferTransactionAmountToday = amount;

                                transferTransactionCount += 1;
                                transferTransactionAmount += amount;

                                trxTransferCountTillDate += 1;
                                trxTransferAmountTillDate += amount;
                            }
                        }
                    }


                    TOTAL_TX:
                    {
                        totalTransactionCount += 1;
                        totalTransactionAmount += amount;

                        trxCountTillDate += 1;
                        trxAmountTillDate += amount;
                    }

                    currentDate = transactionDate;

                    [
                            "transactionDate"               : transactionDate,

                            "totalTransactionCount"         : totalTransactionCount,
                            "totalTransactionAmount"        : totalTransactionAmount,

                            "topupTransactionCount"         : topupTransactionCount,
                            "topupTransactionAmount"        : topupTransactionAmount,
                            "topupTransactionCountToday"    : topupTransactionCountToday,
                            "topupTransactionAmountToday"   : topupTransactionAmountToday,

                            "transferTransactionCount"      : transferTransactionCount,
                            "transferTransactionAmount"     : transferTransactionAmount,
                            "transferTransactionCountToday" : transferTransactionCountToday,
                            "transferTransactionAmountToday": transferTransactionAmountToday,

                            "voucherTransactionCount"       : voucherTransactionCount,
                            "voucherTransactionAmount"      : voucherTransactionAmount,
                            "voucherTransactionCountToday"  : voucherTransactionCountToday,
                            "voucherTransactionAmountToday" : voucherTransactionAmountToday
                    ]
                }
            }

    int i = 0;

    private def updateAggregation(List aggregation) {

        if (aggregation) {

            def sql = """INSERT INTO ${TABLE} 
                        (DATE_UPDATED, TOTAL_TRANSACTIONS_LATEST_COUNT, TOTAL_TRANSACTIONS_LATEST_AMOUNT, TOTAL_TOPUP_LATEST_COUNT, TOTAL_TOPUP_LATEST_AMOUNT, DATED_TOPUP_COUNT, DATED_TOPUP_AMOUNT, TOTAL_TRANSFER_LATEST_COUNT, TOTAL_TRANSFER_LATEST_AMOUNT, DATED_TRANSFER_COUNT, DATED_TRANSFER_AMOUNT, TOTAL_VOUCHER_LATEST_COUNT, TOTAL_VOUCHER_LATEST_AMOUNT, DATED_VOUCHER_COUNT, DATED_VOUCHER_AMOUNT) 
                        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
						
						ON DUPLICATE KEY UPDATE 
						
						TOTAL_TRANSACTIONS_LATEST_COUNT = TOTAL_TRANSACTIONS_LATEST_COUNT + VALUES(TOTAL_TRANSACTIONS_LATEST_COUNT),
						TOTAL_TRANSACTIONS_LATEST_AMOUNT = TOTAL_TRANSACTIONS_LATEST_AMOUNT + VALUES(TOTAL_TRANSACTIONS_LATEST_AMOUNT),						
						
						TOTAL_TOPUP_LATEST_COUNT = TOTAL_TOPUP_LATEST_COUNT + VALUES(TOTAL_TOPUP_LATEST_COUNT),
						TOTAL_TOPUP_LATEST_AMOUNT = TOTAL_TOPUP_LATEST_AMOUNT + VALUES(TOTAL_TOPUP_LATEST_AMOUNT),
						DATED_TOPUP_COUNT = DATED_TOPUP_COUNT + VALUES(DATED_TOPUP_COUNT),
						DATED_TOPUP_AMOUNT = DATED_TOPUP_AMOUNT + VALUES(DATED_TOPUP_AMOUNT),
						
						TOTAL_TRANSFER_LATEST_COUNT = TOTAL_TRANSFER_LATEST_COUNT + VALUES(TOTAL_TRANSFER_LATEST_COUNT),
						TOTAL_TRANSFER_LATEST_AMOUNT = TOTAL_TRANSFER_LATEST_AMOUNT + VALUES(TOTAL_TRANSFER_LATEST_AMOUNT),
                        DATED_TRANSFER_COUNT = DATED_TRANSFER_COUNT + VALUES(DATED_TRANSFER_COUNT),
                        DATED_TRANSFER_AMOUNT = DATED_TRANSFER_AMOUNT + VALUES(DATED_TRANSFER_AMOUNT),
                        
                        TOTAL_VOUCHER_LATEST_COUNT = TOTAL_VOUCHER_LATEST_COUNT + VALUES(TOTAL_VOUCHER_LATEST_COUNT),
                        TOTAL_VOUCHER_LATEST_AMOUNT = TOTAL_VOUCHER_LATEST_AMOUNT + VALUES(TOTAL_VOUCHER_LATEST_AMOUNT),
                        DATED_VOUCHER_COUNT = DATED_VOUCHER_COUNT + VALUES(DATED_VOUCHER_COUNT),
                        DATED_VOUCHER_AMOUNT = DATED_VOUCHER_AMOUNT + VALUES(DATED_VOUCHER_AMOUNT)
						"""

            log.info("\n\n\n Inserting Record #"+ (i+1) +" std_mobile_transactions_sum_aggregation")

            def batchUpdate = jdbcTemplate.batchUpdate(sql, [

                    setValues   :
                            { ps, i ->

                                def row = aggregation[i]

                                ps.setTimestamp(1, toSqlTimestamp(row.transactionDate));
                                ps.setBigDecimal(2, row.totalTransactionCount);
                                ps.setDouble(3, row.totalTransactionAmount);

                                ps.setBigDecimal(4, row.topupTransactionCount);
                                ps.setDouble(5, row.topupTransactionAmount);
                                ps.setBigDecimal(6, row.topupTransactionCountToday);
                                ps.setDouble(7, row.topupTransactionAmountToday);

                                ps.setBigDecimal(8, row.transferTransactionCount);
                                ps.setDouble(9, row.transferTransactionAmount);
                                ps.setBigDecimal(10, row.transferTransactionCountToday);
                                ps.setDouble(11, row.transferTransactionAmountToday);

                                ps.setBigDecimal(12, row.voucherTransactionCount);
                                ps.setDouble(13, row.voucherTransactionAmount);
                                ps.setBigDecimal(14, row.voucherTransactionCountToday);
                                ps.setDouble(15, row.voucherTransactionAmountToday);
                            },

                    getBatchSize: { aggregation.size() }

            ] as BatchPreparedStatementSetter)
        }
    }
}
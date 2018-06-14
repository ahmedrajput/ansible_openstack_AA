package se.seamless.ers.components.dataaggregator.aggregator

import com.seamless.ers.interfaces.platform.clients.transaction.model.ERSTransactionResultCode
import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional


/**
 * Keeps the record of the bulk credit transfers and bulk topups.
 * Reports created from this aggregator are : <br/>
 * @author Danish Amjad
 */

@Log4j
@DynamicMixin
public class StdBulkTransactionsDetailAggregator extends AbstractAggregator {
    private static final def TABLE = "std_bulk_transactions_detail_aggregation"

    @Value('${StdBulkTransactionsDetailAggregator.batch:1000}')
    int limit

    static final def ALLOWED_PROFILES = [
            "TOPUP",
            "CREDIT_TRANSFER"
    ]


    @EqualsAndHashCode
    @ToString
    private static class Key {

        String batchId
        String ersReference
        String resellerMSISDN
        String receiverMSISDN
        Date transactionDateTime
        BigDecimal transactionAmount
        String transactionCurrency
        String transactionStatus
        String failureReason
        String profileId

    }

    @Transactional
    @Scheduled(cron = '${StdBulkTransactionsDetailAggregator.cron:0 0/30 * * * ?}')
    public void aggregate() {
        log.info("Started aggregation ...")
        int aggregationCount = 0;
        def transactions = getTransactions(limit)

        if (transactions) {
            def aggregation = findAllowedProfiles(transactions, ALLOWED_PROFILES)
                    .collect(transactionInfoHashMap)
                    .findAll()
                    .groupBy(key)
                    .collect()

            updateAggregation(aggregation)

            updateCursor(transactions)
            schedule()
        }
        log.info("Ended aggregation. ${aggregationCount} transactions are aggregated!")
    }


    private def transactionInfoHashMap =
            {

                def tr = parser.parse(it.getJSON())?.getAsJsonObject()
                log.debug("JSON received - " + tr.toString())

                def propsMap = tr.get("transactionProperties")?.getAsJsonObject()?.
                        get("map")?.getAsJsonObject()
                def batchId = propsMap.get("import_batch_id")?.getAsString();
                if(!batchId) {
                    return null
                }

                def ersReference = tr.get("ersReference")?.getAsString()
                def resellerMSISDN = getSenderMSISDN(tr)
                def receiverMSISDN = getReceiverMSISDN(tr)
                def transactionDateTime = it.getEndTime();
                def transactionAmount = collectAmount(tr, it.profile)
                def transactionCurrency = collectCurrency(tr)
                def transactionStatus = successfulTransactions(it) ? "SUCCESS" : "FAILED"
                def failureReason = successfulTransactions(it) ? "SUCCESS" : ERSTransactionResultCode.lookupResultCode(it.resultCode)

                //HashMap
                [
                        batchId            : batchId,
                        ersReference       : ersReference,
                        resellerMSISDN     : resellerMSISDN,
                        receiverMSISDN     : receiverMSISDN,
                        transactionDateTime: transactionDateTime,
                        transactionAmount  : transactionAmount,
                        transactionCurrency: transactionCurrency,
                        transactionStatus  : transactionStatus,
                        failureReason      : failureReason,
                        profileId          : it.profile

                ]


            }

    private key =
            {
                log.debug("Creating key from ${it}")
                new Key
                        (
                                batchId: it.batchId,
                                ersReference: it.ersReference,
                                resellerMSISDN: it.resellerMSISDN,
                                receiverMSISDN: it.receiverMSISDN,
                                transactionDateTime: it.transactionDateTime,
                                transactionAmount: it.transactionAmount,
                                transactionCurrency: it.transactionCurrency,
                                transactionStatus: it.transactionStatus,
                                failureReason: it.failureReason,
                                profileId: it.profileId
                        )
            }


    private def updateAggregation(List aggregation) {


        if (aggregation) {

            // upsert  data
            def sql = "insert into ${TABLE} (batch_id ,ers_reference ,reseller_msisdn ,receiver_msisdn ,transaction_date_time,transaction_amount ,transaction_currency ,transaction_status ,failure_reason ,profile_id) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"

            log.debug("Using sql query for Insert - " + sql)


            int index = 0;
            jdbcTemplate.batchUpdate(sql, [
                    setValues   : { ps, i ->
                        index = 0;
                        ps.setString(++index, aggregation[i].key.batchId)
                        ps.setString(++index, aggregation[i].key.ersReference)
                        ps.setString(++index, aggregation[i].key.resellerMSISDN)
                        ps.setString(++index, aggregation[i].key.receiverMSISDN)
                        ps.setTimestamp (++index, toSqlTimestamp(aggregation[i].key.transactionDateTime))
                        ps.setBigDecimal(++index, aggregation[i].key.transactionAmount)
                        ps.setString(++index, aggregation[i].key.transactionCurrency)
                        ps.setString(++index, aggregation[i].key.transactionStatus)
                        ps.setString(++index, aggregation[i].key.failureReason)
                        ps.setString(++index, aggregation[i].key.profileId)

                    },
                    getBatchSize: { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}

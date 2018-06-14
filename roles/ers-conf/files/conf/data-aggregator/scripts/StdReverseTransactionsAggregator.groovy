package se.seamless.ers.components.dataaggregator.aggregator

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
/**
 * Aggregates Details of Reverse Transactions

 * @author Bilal.Mirza
 */
@Log4j
@DynamicMixin
class StdReverseTransactionsAggregator extends AbstractAggregator
{
    static final def TABLE = "std_reverse_transaction_aggregation"
    static final def REVERSAL_PROFILES = [
            "REVERSE_TOPUP",
            "REVERSE_CREDIT_TRANSFER"
    ]

    @Value('${StdReverseTransactionsAggregator.batch:1000}')
    int limit

    @EqualsAndHashCode
    @ToString
    private static class Key
    {
        String ersReference
        String referredErsReference
        Date date
        String senderMSISDN
        String receiverMSISDN
        String transactionType
        BigDecimal senderBalanceBefore
        BigDecimal senderBalanceAfter
        BigDecimal receiverBalanceBefore
        BigDecimal receiverBalanceAfter
        BigDecimal transactionAmount
        String transactionStatus
        String userApprovedBy
        String userSubmittedBy

    }
    @Transactional
    @Scheduled(cron = '${StdReverseTransactionsAggregator.cron:0 0/2 * * * ?}')
    void aggregate()
    {
        def transactions = getTransactions(limit)
        if (transactions)
        {
            def aggregation = findAllowedProfiles(transactions, REVERSAL_PROFILES)
                    .findAll(successfulTransactions)
                    .collect(transactionDetails)
                    .findAll()
                    .groupBy(key)
                    .collect(statistics)
            updateAggregation(aggregation)
            updateCursor(transactions)
            schedule()
        }
    }

    private transactionDetails =
            {
                log.debug("++++++++++++++++++++++ Processing transaction: ${it} ++++++++++++++++++++++++++++++");
    
                def tr = parser.parse(it.getJSON()).getAsJsonObject()
    
                log.debug("******************************* Transaction Data ************************************" )
                log.debug("${tr}")
                log.debug("*************************************************************************************" )
    
    
                def profileId = it.profile
                def channel = asString(tr, "channel")
                def resultProperties = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()
                def submittedBy = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("REQUEST_REVERSAL_BY")?.getAsString()
                def approvedBy = tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("REVERSAL_APPROVED_BY")?.getAsString()
                def ersReference = asString(tr, "ersReference")
                def requestType = asString(tr, "requestType")
                def transactionStatus = asString(tr, "state")
                
                if(transactionStatus.equals("Reversed") && requestType.equals("REQUEST_REVERSAL"))
                {
                    approvedBy = submittedBy
                }
                def referredErsReference = asString(tr, "referredTransactionErsReference")
                def date = it.getStartTime().clone().clearTime()
                def senderData = findSenderPrincipalAlongWithTransactionRow(tr)
                def senderMSISDN = getSenderMSISDN(tr)
                def receiverMSISDN = getReceiverMSISDN(tr)
                def transactionType = translatedTransactionProfile(channel, profileId)
                def transactionAmount = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "amount")?.abs()
                def senderBalanceBefore = getSenderBalanceBefore(tr).toBigDecimal()
                def senderBalanceAfter = getSenderBalanceAfter(tr).toBigDecimal()
                def receiverBalanceBefore = getReceiverBalanceBefore(tr)==null ? new BigDecimal(0) : getReceiverBalanceBefore(tr).toBigDecimal()
                def receiverBalanceAfter = getReceiverBalance(tr)== null ? new BigDecimal(0) : getReceiverBalance(tr)?.toBigDecimal()

                [
                        ersReference          : ersReference,
                        referredErsReference : referredErsReference,
                        date                  : date,
                        senderMSISDN          : senderMSISDN,
                        receiverMSISDN        : receiverMSISDN,
                        transactionType       : transactionType,
                        senderBalanceBefore   : senderBalanceBefore,
                        senderBalanceAfter    : senderBalanceAfter,
                        receiverBalanceBefore : receiverBalanceBefore,
                        receiverBalanceAfter  : receiverBalanceAfter,
                        transactionAmount     : transactionAmount,
                        transactionStatus     : transactionStatus,
                        approvedBy            : approvedBy,
                        submittedBy           : submittedBy
                ]
            }

    private def key =
            {
                log.debug("Creating key from ${it}")
                [
                        ersReference          : it.ersReference,
                        referredErsReference  : it.referredErsReference,
                        date                  : it.date,
                        senderMSISDN          : it.senderMSISDN,
                        receiverMSISDN        : it.receiverMSISDN,
                        transactionType       : it.transactionType,
                        senderBalanceBefore   : it.senderBalanceBefore,
                        senderBalanceAfter    : it.senderBalanceAfter,
                        receiverBalanceBefore : it.receiverBalanceBefore,
                        receiverBalanceAfter  : it.receiverBalanceAfter,
                        transactionAmount     : it.transactionAmount,
                        transactionStatus     : it.transactionStatus,
                        userApprovedBy        : it.approvedBy,
                        userSubmittedBy       : it.submittedBy
                ]
            }

    private statistics =
            {	key, list ->
                [
                        key             : key
                ]
            }

    private updateAggregation(List aggregation)
    {
        log.info("Aggregated into ${aggregation.size()} rows.")
        if(aggregation)
        {
            def sql = "INSERT INTO ${TABLE} (transactionDate, transactionReference, referredTransactionReference , senderMSISDN, receiverMSISDN, transactionType, transactionAmount, senderBalanceBefore, senderBalanceAfter, receiverBalanceBefore, receiverBalanceAfter, transactionStatus, userApprovedBy, userSubmittedBy ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues:
                            { ps, i ->
                                def row = aggregation[i]
                                int index=0
                                ps.setDate(++index,toSqlDate(row.key.date))
                                ps.setString(++index, row.key.ersReference)
                                ps.setString(++index, row.key.referredErsReference)
                                ps.setString(++index,row.key.senderMSISDN)
                                ps.setString(++index,row.key.receiverMSISDN)
                                ps.setString(++index,row.key.transactionType)
                                ps.setBigDecimal(++index,row.key.transactionAmount)
                                ps.setBigDecimal(++index,row.key.senderBalanceBefore)
                                ps.setBigDecimal(++index, row.key.senderBalanceAfter)
                                ps.setBigDecimal(++index, row.key.receiverBalanceAfter)
                                ps.setBigDecimal(++index, row.key.receiverBalanceAfter)
                                ps.setString(++index,row.key.transactionStatus)
                                ps.setString(++index,row.key.userApprovedBy)
                                ps.setString(++index,row.key.userSubmittedBy)
                            },
                    getBatchSize:
                            { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}

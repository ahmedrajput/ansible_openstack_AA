package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

/**
 * Aggregates transaction rows per reseller and calculates current Balance of each reseller.
 *
 * @author Saira Arif
 */
 
 
@Log4j
@DynamicMixin
public class StdBalanceReportAggregator extends AbstractAggregator
{
    static final def INOUT_TABLE = "std_balance_report_aggregation"

    @Value('${StdBalanceReportAggregator.batch:1000}')
    int limit

    def ALLOWED_PROFILES = [
    		"PURCHASE",
            "TOPUP",
            "CREDIT_TRANSFER",
            "REVERSE_CREDIT_TRANSFER",
            "REVERSAL",
            "VOS_PURCHASE",
            "VOT_PURCHASE",
            "REVERSE_VOS_PURCHASE",
            "REVERSE_VOT_PURCHASE"
    ]
    @EqualsAndHashCode
    @ToString
    private static class Key  {
        String resellerId
    }

    @Transactional
    @Scheduled(cron  = '${StdBalanceReportAggregator.cron:0 0/30 * * * ?}')
    public void aggregate()
    {
        log.info("Started aggregation ...")
        int aggregationCount = 0;
        def transactions = getTransactions(limit)
        if (transactions) {
            def aggregation = findAllowedProfiles(transactions,ALLOWED_PROFILES).findAll(successfulTransactions)
            log.debug("StdBalanceReportAggregator fetching transaction")
            List filteredList = prepareAggregationForInsertion(aggregation)
            aggregationCount = filteredList?.size()
            updateAggregation(filteredList)
            updateCursor(transactions)
            schedule(50, TimeUnit.MILLISECONDS)
        }

        log.info("Ended aggregation. ${aggregationCount} resellers are aggregated!")
    }

    private def prepareAggregationForInsertion (List aggregation) {

        log.debug("Preparing aggregation. List: " + aggregation)
        def finalList= new ArrayList();

        if (aggregation) {
            def resellerBalances = aggregation.collectMany
            {
            	def tr = parser.parse(it.getJSON()).getAsJsonObject()
                def profileId = tr.get("profileId")?.getAsString()
                def endDateStr = asString(tr,"endTime")
                def endTime =  it.getEndTime();
                def senderData
                def resellerId
                def accountId
                def currency
                def balanceAfter=BigDecimal.ZERO
                def senderMap
                def receiverMap
                
                def transactionRows=new ArrayList()
                
                senderData = findSenderPrincipalAlongWithTransactionRow(tr)
                if (senderData) {

                    resellerId =  asString(senderData?.principal, "resellerId")
                    senderData?.principal?.get("accounts")?.getAsJsonArray()?.iterator()?.collect
                            {
                                accountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
                            }
                    balanceAfter = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "balanceAfter")
                    currency = collectCurrency(tr)


                    log.debug(" resellerId: ${resellerId}, balanceAfter : ${balanceAfter} , endTime : ${endTime.format("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")}" )

                    senderMap = [ 
                    				resellerId: resellerId, 
                    				balanceCurrency: currency , 
                    				balanceAfter: balanceAfter, 
                    				endTime:endTime, 
                    				accountId:accountId
                    		   ]
                    //Merging senderMap
                   	transactionRows.add(senderMap)
                }


                def receiverData = findReceiverPrincipalAlongWithTransactionRow(tr)
                if (receiverData && !(profileId =="TOPUP" || profileId=="PURCHASE")) {

                    resellerId =  asString(receiverData?.principal, "resellerId")
                    receiverData?.principal?.get("accounts")?.getAsJsonArray()?.iterator()?.collect
                      		{
                                accountId=it.get("accountSpecifier")?.getAsJsonObject()?.get("accountId")?.getAsString()
                            }
                    balanceAfter = getBigDecimalFieldFromAnyField(receiverData.transactionRow, "value", "balanceAfter")
                    currency = collectCurrency(tr)
                    receiverMap = [ 
                    				resellerId: resellerId,
                    				balanceCurrency: currency ,
                    				balanceAfter: balanceAfter,
                    				endTime:endTime,
                    				accountId:accountId
                    			   ]
                    //Merging receiverMap
                    transactionRows.add(receiverMap)
                }
               
                return transactionRows

            }.findAll {
                it != null && it.resellerId
            }


            log.debug("resellerBalances size()" +resellerBalances.size())
            finalList = resellerBalances.sort {it.endTime}
                    .groupBy {
                new Key( resellerId: it.resellerId)
            }.collect { key, list -> [resellerId : list.first().resellerId, balanceCurrency:list.first().balanceCurrency,  currentBalance: list.last().balanceAfter, accountId:list.first().accountId ]
            }

        }
        log.debug("Prepared aggregation. Prepared list : ${finalList}")
        return finalList;
    }


    private def updateAggregation(List aggregation)
    {
        log.debug("Aggregated into ${aggregation.size()} rows.")
        if(aggregation)
        {

            def sql = "update ${INOUT_TABLE} set currentBalance= ?,currency =? WHERE accountId = ? "
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues:
                            {	ps, i ->
                                def row = aggregation[i]
                                ps.setString(1, row.currentBalance.toString())
                                ps.setString(2, row.balanceCurrency.toString())
                                ps.setString(3, row.accountId.toString().toUpperCase())


                            },
                    getBatchSize:
                            { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}


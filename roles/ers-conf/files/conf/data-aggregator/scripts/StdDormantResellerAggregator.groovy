package se.seamless.ers.components.dataaggregator.aggregator

import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import java.util.concurrent.TimeUnit
/**
 * Update last transaction for each reseller. Calculate balance and credit given since activation.
 * @author Muhammad Asad Ullah Khan
 */
@Log4j
@DynamicMixin
public class StdDormantResellerAggregator extends AbstractAggregator
{
    static final TABLE = "std_dormant_reseller_aggregation"
    
    @Value('${StdDormantResellerAggregator.batch:1000}')
    int limit
    
    @EqualsAndHashCode
    @ToString
    private static class Key
    {
        String ersReference
        String resellerId
        String resellerMSISDN
        String receiverMSISDN
        String resellerName
        String resellerLevel
        String accountTypeId
        String accountId
        BigDecimal balance
        Date date
        Date lastTransactionTime
    }
    
    @Transactional
    @Scheduled(cron = '${StdDormantResellerAggregator.cron:0 0/30 * * * ?}')
    void aggregate()
    {
        
        def transactions = getTransactions(limit)
        
        if (!transactions)
        {
            log.info("No transactions returned")
            return
        }
        
        def aggregation = transactions.collectMany
        {
            def date = it.getEndTime().clone().clearTime()
            def lastTransactionTime = it.getEndTime()
            def tr = parser.parse(it.getJSON()).getAsJsonObject()
            def resultCode = tr?.get("resultCode")?.getAsInt()
            def ersReference = tr?.get("ersReference")?.getAsString()
            String receiverId = "";
            
            def transactionType = it.getProfile()
            def transactionAmount = collectAmount(tr, it.profile)
            def transactionCurrency = collectCurrency(tr)
            def receiverMSISDN = ""
            if (tr.get("topupPrincipal"))
            {
                receiverMSISDN = tr.get("topupPrincipal")?.getAsJsonObject()?.get("subscriberMSISDN")?.getAsString()
            }
            else if (tr.get("receiverPrincipal"))
            {
                receiverMSISDN = tr.get("receiverPrincipal")?.getAsJsonObject()?.get("resellerMSISDN")?.getAsString() ?: tr.get("receiverPrincipal")?.getAsJsonObject()?.
                        get("subscriberMSISDN")?.getAsString()
                receiverId = tr.get("receiverPrincipal")?.getAsJsonObject()?.get("resellerId")?.getAsString()
            }
            
            
            
            log.debug("***************transaction\n" + tr + "\n***************")
            int transactionRowNumber = 0
            tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
                log.debug("process transaction row:" + it)
                transactionRowNumber++;
                def principal = findPrincipalWithAccount(tr, it.get("accountSpecifier"))
                def principalSpecifier = it.get("principalType")?.getAsString()
                def resellerId = asString(principal, "resellerId")
                def resellerMSISDN = asString(principal, "resellerMSISDN")
                def resellerName = asString(principal, "resellerName")
                def resellerLevel = asString(principal, "resellerTypeName")
                def balance;
                
                if (transactionRowNumber > 1 && !receiverId.equals("") && resultCode == 0)
                {
                    if (getReceiverBalance(tr))
                    {
                        balance = new BigDecimal(getReceiverBalance(tr) ?: 0)
                    }
                }
                else if (resultCode == 0)
                {
                    balance = new BigDecimal(getSenderBalance(tr) ?: 0)
                }
                
                // get account details and amount trigger on this account
                def accountId = asStringFromField(it, "accountSpecifier", "accountId")
                def accountTypeId = asStringFromField(it, "accountSpecifier", "accountTypeId")
                def accountAmount = getBigDecimalFieldFromAnyField(it, "value", "amount")
                def accountCurrency = getStringFieldFromAnyField(it, "currency", "amount")
                [
                        ersReference       : ersReference,
                        resellerMSISDN     : resellerMSISDN,
                        resellerId         : resellerId,
                        resellerName       : resellerName,
                        resellerLevel      : resellerLevel,
                        accountId          : accountId,
                        accountTypeId      : accountTypeId,
                        accountAmount      : accountAmount,
                        accountCurrency    : accountCurrency,
                        date               : date,
                        resultCode         : resultCode,
                        transactionType    : transactionType,
                        transactionAmount  : transactionAmount,
                        transactionCurrency: transactionCurrency,
                        receiverMSISDN     : receiverMSISDN,
                        balance            : balance,
                        lastTransactionTime: lastTransactionTime,
                        principalSpecifier : principalSpecifier,
                ]
            }
            
        }.findAll {
            log.debug("findAll:" + it)
            it.resultCode == 0 && it.resellerId != null && it.amount != 0
        }.groupBy {
            new Key( ersReference: it.ersReference, resellerMSISDN: it.resellerMSISDN, receiverMSISDN: it.receiverMSISDN, resellerId: it.resellerId, resellerName: it.resellerName, resellerLevel: it.resellerLevel,
                    accountId: it.accountId, accountTypeId: it.accountTypeId, balance: it.balance, lastTransactionTime: it.lastTransactionTime)
        }.collect { key, list ->
            //aggregate total credit since activation, account balance, number of transaction,  last transaction
            [key                : key, transactionCount: list.size(), balanceChange: list.sum {
                it.accountAmount
            }, newCredit        : list.sum { it.accountAmount > 0 ? it.accountAmount : 0 },
             lastTransactionDate: list.last().date, lastTransactionType: list.last().transactionType, lastTransactionAmount: list.last().transactionAmount, lastTransactionCurrency: list.last().transactionCurrency, balance: list.last().balance, date: list.last().date, lastTransactionTime: list.last().lastTransactionTime, principalSpecifier: list.last().principalSpecifier]
        }
        
        updateAggregation(aggregation)
        
        updateCursor(transactions)
        
        schedule(50, TimeUnit.MILLISECONDS)
    }
    
    private def updateAggregation = { aggregation ->
        log.info("Aggregated into ${aggregation.size()} rows.")
        if (aggregation)
        {
            def sql =
                    """INSERT INTO ${TABLE}
                        (keyHash, resellerId, resellerMSISDN, resellerName, resellerLevel, accountTypeId, accountId,transactionCount, balance, totalCredit, lastTransactionDate, lastTransactionType, lastTransactionAmount, lastTransactionCurrency, receiverMSISDN,lastTransactionTime, principalSpecifier,lastTransactionId)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE 
                        transactionCount=transactionCount+VALUES(transactionCount),
                        balance=VALUES(balance),
                        totalCredit=totalCredit+VALUES(totalCredit),
                        lastTransactionDate=VALUES(lastTransactionDate),
                        lastTransactionType=VALUES(lastTransactionType),
                        lastTransactionAmount=VALUES(lastTransactionAmount),
                        lastTransactionCurrency=VALUES(lastTransactionCurrency),
                        lastTransactionTime=VALUES(lastTransactionTime),
                        lastTransactionId=VALUES(lastTransactionId)
                    """
            aggregation.each { row ->
                def batchUpdate = jdbcTemplate.update(sql, [setValues: { ps ->
                    ps.setInt(1, row.key.hashCode())
                    ps.setString(2, row.key.resellerId)
                    ps.setString(3, row.key.resellerMSISDN)
                    ps.setString(4, row.key.resellerName)
                    ps.setString(5, row.key.resellerLevel)
                    ps.setString(6, row.key.accountTypeId)
                    ps.setString(7, row.key.accountId)
                    ps.setInt(8, row.transactionCount)
                    ps.setBigDecimal(9, row.key.balance)
                    ps.setBigDecimal(10, row.newCredit)
                    ps.setDate(11, new java.sql.Date(row.lastTransactionDate.getTime()))
                    ps.setString(12, row.lastTransactionType)
                    ps.setBigDecimal(13, row.lastTransactionAmount)
                    ps.setString(14, row.lastTransactionCurrency)
                    ps.setString(15, row.key.receiverMSISDN)
                    ps.setTimestamp(16, toSqlTimestamp(row.lastTransactionTime))
                    ps.setString(17, row.principalSpecifier)
                    if (row.principalSpecifier.toString().equals("Sender"))
                    {
                        update_batch_date(row.key.resellerId.toString(), new java.sql.Date(row.lastTransactionDate.getTime()));
                    }
                    ps.setString(18, row.key.ersReference)
                }] as PreparedStatementSetter)
            }
            
        }
    }
    
    public update_batch_date(String id, Date dates)
    {
        def sql =
                """UPDATE std_dormant_reseller_aggregation
		SET lastTransactionDate = ? WHERE resellerId=?
			"""
        
        def batchUpdate = jdbcTemplate.update(sql, [setValues: { ps ->
            ps.setString(2, id)
            ps.setDate(1, dates)
            
        }] as PreparedStatementSetter)
        
    }
    
    
}

package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

/**
 * Aggregates transaction rows per reseller with corresponding
 * balance before and after values.
 *
 * @author Tirthankar Mitra
 */
@Log4j
@DynamicMixin
public class StdResellerDailyBalanceAggregator extends AbstractAggregator
{
    static final def DAILY_BALANCE_TABLE = "std_region_reseller_account_statement_daily_balance_aggregation"
    
    static final
    def ACCOUNTSSQL = "SELECT t.accountId AS resellerId, t.balanceBefore, t.balanceAfter, t.createDate AS date  FROM transactions t WHERE t.createDate > ? and t.createDate <= ? ORDER BY t.transactionKey"
    
    static final def dateFormat = "yyyy-MM-dd HH:mm:ss"
    
    
    @Autowired
    @Qualifier("accounts")
    private JdbcTemplate accounts
    
    @Transactional
    @Scheduled(cron = '${StdResellerDailyBalanceAggregator.cron}')
    public void aggregate()
    {
        def todayDate = new Date()
        def date = getDate()
        def accountTransactions = accounts.query(ACCOUNTSSQL, [setValues: { ps ->
            use(TimeCategory)
            {
                ps.setString(1, date.format(dateFormat))
                ps.setString(2, todayDate.format(dateFormat))
            }
        }] as PreparedStatementSetter, new ColumnMapRowMapper())
        
        log.info("Got ${accountTransactions.size()} account transactions from accounts")
        
        if (accountTransactions)
        {
            
            def sql = "insert into ${DAILY_BALANCE_TABLE} (aggregationDate, resellerId, balanceBefore, balanceAfter) values (?, ?, ?, ?) ON DUPLICATE KEY UPDATE balanceAfter=VALUES(balanceAfter)"
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues:
                            { ps, i ->
                                def onlyDate = accountTransactions[i].date.clearTime()
                                log.debug("row: " + accountTransactions[i])
                                ps.setTimestamp(1, toSqlTimestamp(onlyDate))
                                ps.setString(2, accountTransactions[i].resellerId)
                                ps.setBigDecimal(3, accountTransactions[i].balanceBefore)
                                ps.setBigDecimal(4, accountTransactions[i].balanceAfter)
                            },
                    getBatchSize:
                            { accountTransactions.size() }
            ] as BatchPreparedStatementSetter)
        }
        updateCursor(todayDate.format(dateFormat))
    }
    
    private getDate =
    {
        def cursor = getCursor()
        def date = toSqlDate(new Date())
        if (cursor)
        {
            use(TimeCategory)
            {
                date = cursor
            }
        }
        else
        {
            use(TimeCategory)
            {
                date = toSqlDate(date - 10.days)
            }
        }
        return date
    }
}

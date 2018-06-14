package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import java.math.BigDecimal;
import java.util.Date;
import java.util.concurrent.TimeUnit

import org.apache.commons.lang.time.DateUtils
import org.eclipse.jetty.util.log.Log;
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.ResultSetExtractor
import org.springframework.jdbc.core.RowCallbackHandler

/**
 * Aggregates transaction rows per reseller and calculates current Balance of each reseller.
 *
 * @author Bilal Ilyas
 */
 
@Log4j
@DynamicMixin
public class StdDetailBalanceReportAggregator extends AbstractAggregator
{
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
	static final def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
	
    static final def INOUT_TABLE = "dataaggregator.std_detail_balance_report_aggregation"
    static final def QUERY_TRANSF_IN = "SELECT receiverResellerID as 'resellerId',count(*) as 'countTransferIn',sum(amount) as 'balanceTransferIn' FROM dataaggregator.std_daily_transaction_summary_aggregation where transactionType='CREDIT_TRANSFER' and resultStatus='Success' AND DATE_FORMAT(transactionDate,'%Y-%m-%d') =DATE_FORMAT(DATE_SUB(NOW(),INTERVAL 1 DAY),'%Y-%m-%d') GROUP By receiverResellerID"
    static final def QUERY_TRANSF_OUT = "SELECT senderResellerID as 'resellerId',count(*) as 'countTransferOut',sum(amount) as 'balanceTransferOut' FROM dataaggregator.std_daily_transaction_summary_aggregation WHERE transactionType IN ('TOPUP','CREDIT_TRANSFER','PURCHASE') AND resultStatus='Success' AND DATE_FORMAT(transactionDate,'%Y-%m-%d') =DATE_FORMAT(DATE_SUB(NOW(),INTERVAL 1 DAY),'%Y-%m-%d') GROUP By senderResellerID "
    static final def QUERY_CLOSING_BALANCE = "SELECT accountId,balance FROM accounts.accounts"
    static final def QUERY_OPENING_BALANCE = "SELECT accountId,closingBalance FROM ${INOUT_TABLE} where accountId IS NOT NULL AND closingBalance IS NOT NULL"
    static final def RESELLERBALANCEREPORTSQL ="""
    		SELECT commres.tag as reseller_id,
            commres.name as reseller_name,
            extdev.address as MSISDN,
            (case SUBSTRING_INDEX(SUBSTRING_INDEX(commres.reseller_path,'/',-1),'/',1) when SUBSTRING_INDEX(SUBSTRING_INDEX(commres.reseller_path,'/',-2),'/',1) then null else SUBSTRING_INDEX(SUBSTRING_INDEX(commres.reseller_path,'/',-2),'/',1) end) as reseller_parentname,
            (case commres.status when '0' then 'Active' when '1' then 'InActive' when '2' then 'Blocked' when '3' then 'Frozen' else 'Disabled' end) as reseller_status,
            restype.name as reseller_type, account.balance as current_balance,
            commres.reseller_path as reseller_path,
            UPPER(account.accountId) as account_id,
            commres.last_modified as commres_last_modified,
            extdev.last_modified as extdev_last_modified,
            commcont.last_modified as commcont_last_modified,
            ppacc.last_modified as ppacc_last_modified,
           (select MAX(accounts.transactions.createDate) from accounts.transactions where accounts.transactions.accountTypeId = 'RESELLER_AIRTIME' and accounts.transactions.accountId = ppacc.account_nr) as last_transaction_date           
            FROM Refill.commission_receivers commres
            LEFT JOIN Refill.extdev_devices extdev ON (extdev.owner_key = commres.receiver_key)
            LEFT JOIN Refill.commission_contracts commcont ON (commcont.contract_key=commres.contract_key)  
            LEFT JOIN Refill.reseller_types restype ON (restype.type_key=commcont.reseller_type_key)
            LEFT JOIN Refill.pay_prereg_accounts ppacc ON (ppacc.owner_key=commres.receiver_key)
            LEFT JOIN accounts.accounts account ON account.accountId=ppacc.account_nr
              WHERE commres.last_modified >= ?
				OR extdev.last_modified >= ?
				OR commcont.last_modified >= ?
				OR ppacc.last_modified >=  ?
				OR (select MAX(accounts.transactions.createDate) from accounts.transactions where accounts.transactions.accountTypeId = 'RESELLER_AIRTIME' and accounts.transactions.accountId = ppacc.account_nr) >= ?		
			"""

    @Value('${StdDetailBalanceReportAggregator.batch:1000}')
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
    
    @Transactional
    @Scheduled(cron  = '${StdDetailBalanceReportAggregator.cron:0 30 2 * * ?}')
    public void aggregate()
    {
        log.info("Started aggregation ...")
        
        def date = getDate()
        
		def resellers = refill.query(RESELLERBALANCEREPORTSQL, [setValues: { ps ->
				use(TimeCategory) {
					ps.setTimestamp(1, toSqlTimestamp(date))
					ps.setTimestamp(2, toSqlTimestamp(date))
					ps.setTimestamp(3, toSqlTimestamp(date))
					ps.setTimestamp(4, toSqlTimestamp(date))
					ps.setTimestamp(5, toSqlTimestamp(date))
					
				}
			}] as PreparedStatementSetter, new ColumnMapRowMapper())
		
		log.debug("Got StdDetailBalanceReportAggregator ${resellers.size()}  from refill. and resellers are ${resellers}")
		
        addedResellersAggregation(resellers,date)
        
        def reseller_opening_balance = jdbcTemplate.queryForList(QUERY_OPENING_BALANCE)
        log.info("Opening Balance = ${reseller_opening_balance}")
        updateResellerOpeningBalance(reseller_opening_balance)
        
               
    	def reseller_transf_in = jdbcTemplate.queryForList(QUERY_TRANSF_IN)  
		log.info("Transfer In = ${reseller_transf_in}")
		updateResellerTransfIn(reseller_transf_in)
		
		def reseller_transf_out = jdbcTemplate.queryForList(QUERY_TRANSF_OUT)
        log.info("Transfer Out = ${reseller_transf_out}")
        updateResellerTransfOut(reseller_transf_out)
        
        def reseller_closing_balance = refill.queryForList(QUERY_CLOSING_BALANCE)
        updateResellerClosingBalance(reseller_closing_balance)
        log.info("Closing Balance = ${reseller_closing_balance}")
        
        //--updateCursor(transactions)
        //schedule(50, TimeUnit.MILLISECONDS)
        
        log.info("Ended aggregation.")
    }

    private def addedResellersAggregation(List resellers,def date)
    {
        log.debug("Start inserting into DB ${resellers.size()} rows.")
        
        
        if(resellers) {
			log.info("reseller exits")
		 	def sql = """
		 	INSERT INTO ${INOUT_TABLE} 
		 	(resellerId,resellerName,msisdn,resellerParent,resellerStatus,resellerType,currentBalance,accountId,resellerPath,last_transaction_date)
		 	VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) 
		 	ON DUPLICATE KEY UPDATE
		 	resellerName=VALUES(resellerName),
			msisdn=VALUES(msisdn),
		 	resellerParent=VALUES(resellerParent),
		 	resellerStatus=VALUES(resellerStatus),
            resellerType=VALUES(resellerType),
		 	currentBalance=VALUES(currentBalance),
            accountId=VALUES(accountId),
            resellerPath=VALUES(resellerPath),
            last_transaction_date = VALUES(last_transaction_date)
		 	"""
		 	log.debug("Reseller sql : ${sql}")
           
			jdbcTemplate.batchUpdate(sql,[
				setValues: { ps, i ->
					ps.setString(1,resellers[i].reseller_id)
					ps.setString(2,resellers[i].reseller_name)
					ps.setString(3,resellers[i].MSISDN)
					ps.setString(4,resellers[i].reseller_parentname)
					ps.setString(5,resellers[i].reseller_status)
					ps.setString(6,resellers[i].reseller_type)
					ps.setString(7,resellers[i].current_balance.toString())
					ps.setString(8,resellers[i].account_id.toString())
					ps.setString(9,resellers[i].reseller_path.toString())
					ps.setTimestamp(10,resellers[i].last_transaction_date)
					
					def lastModifiedDates = [resellers[i].commres_last_modified ,
											 resellers[i].extdev_last_modified,
											 resellers[i].commcont_last_modified,
											 resellers[i].ppacc_last_modified,
											 resellers[i].last_transaction_date,
											 date ].findAll().sort()
					
					log.info("last modified dates "+lastModifiedDates);
					date = lastModifiedDates.last()
					
				},
				getBatchSize: { resellers.size() }
				] as BatchPreparedStatementSetter)
			log.debug("Data inserted in StdDetailBalanceReportAggregator")
		}
		//update cursor with the modified cursor date
		updateCursor(date.format(dateFormat))
        
    }
    private def updateResellerTransfIn(List aggregation)
    {
        log.debug("Start Updating Reseller Transfer In ")
        if(aggregation)
        {
            		
    		//def sql="INSERT INTO ${INOUT_TABLE}(resellerId,countTransferIn,balanceTransferIn) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE countTransferIn=VALUES(countTransferIn), balanceTransferIn=VALUES(balanceTransferIn) "
			def sql="UPDATE ${INOUT_TABLE} SET countTransferIn=?,balanceTransferIn=? WHERE resellerId =? "
			aggregation.eachWithIndex { row, index ->
				
				def updateStatement = jdbcTemplate.update(sql, [setValues: { ps ->					
	                ps.setInt(1, row.countTransferIn.toInteger())
	                ps.setString(2, row.balanceTransferIn.toString())
					ps.setString(3, row.resellerId.toString())
				}] as PreparedStatementSetter)
			}				
        }
        log.debug("Finish Updating Reseller Transfer In ")
    }
    private def updateResellerTransfOut(List aggregation_trans_out)
    {
        if(aggregation_trans_out)
        {
        	log.debug("Start Updating Balance Transfer Out")
        	
        	def sql_transf_out="UPDATE ${INOUT_TABLE} SET countTransferOut=?,balanceTransferOut=? WHERE resellerId =? "	
        	
        	aggregation_trans_out.eachWithIndex { row, index ->
        	
			def updateStatement_trans_out = jdbcTemplate.update(sql_transf_out, [setValues: { prep_st ->					
					prep_st.setInt(1, row.countTransferOut.toInteger())
                    prep_st.setString(2, row.balanceTransferOut.toString())
					prep_st.setString(3, row.resellerId.toString())								   
				}] as PreparedStatementSetter)	
			}
        	log.debug("Finish Updating Balance Transfer Out")   
		}
        	
           
    }
    private def updateResellerClosingBalance(List aggregation)
    {
        log.debug("Updating Closing Balance")
        if(aggregation)
        {
        	def sql = "UPDATE ${INOUT_TABLE} SET closingBalance = ? WHERE resellerId=? "
			aggregation.eachWithIndex { row, index ->
			
			def updateStatement = jdbcTemplate.update(sql, [setValues: { ps ->
			 ps.setString(1, row.balance.toString())
	         ps.setString(2, row.accountId.toString())
           
			}] as PreparedStatementSetter)										
		}		
            
         log.debug("Finish Updating Closing Balance")   
            
        }
    }
    private def updateResellerOpeningBalance(List aggregation)
    {
        log.debug("Updating Opening Balance")
        if(aggregation)
        {
        	def sql = "UPDATE ${INOUT_TABLE} SET openingBalance=? WHERE resellerId = ? "    	
			aggregation.eachWithIndex { row, index ->
			 def updateStatement = jdbcTemplate.update(sql, [setValues: { ps ->
			 ps.setString(1, row.closingBalance.toString())
             ps.setString(2, row.accountId.toString())
			 	}] as PreparedStatementSetter)										
			}
      
         log.debug("Finish Updating Opening Balance")   
            
        }
    }
    
 // get cursor date ,if it exist-> update date with that cursor date
 	private getDate = {
 		def cursor = getCursor()
 		
 		def date = toSqlTimestamp(new Date())
 		if(cursor) {
 			date = Date.parse(dateFormat, cursor);
 			use(TimeCategory) {
 				date = toSqlTimestamp(date)
 			}
 		}
 		else{
 			use(TimeCategory) {
 				date = toSqlTimestamp(date - 10.years)
 			}
 		}
 		return date
 	}
}


package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter

import java.util.Date;
import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional

import com.seamless.ers.interfaces.platform.clients.transaction.model.ERSTransactionResultCode

import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowCallbackHandler

 /**
 * Aggregates reseller detail information id,name,msisdn,parent,status,path & accountId 
 * and update cursor after any change occurred
 * It dump data to `dataaggregator`.`std_balance_report_aggregation`
 * @author Saira Arif
 */
@Log4j
@DynamicMixin
public class StdBalanceReportInformationAggregator extends AbstractAggregator
{
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
	static final def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
	
	static final def TABLE = "std_balance_report_aggregation"
	
	static final def RESELLERBALANCEREPORTSQL ="""
											 SELECT commres.tag as reseller_id,
                                                commres.name as reseller_name,
                                                extdev.address as MSISDN,
                                                comrcv.tag as reseller_parentname,
                                                (case commres.status when '0' then 'Active' when '1' then 'InActive' when '2' then 'Blocked' when '3' then 'Frozen' else 'Disabled' end) as reseller_status,
                                                restype.name as reseller_type, '0.00' as current_balance,
                                                commres.reseller_path as reseller_path,
                                                UPPER(ppacc.account_nr) as account_id,
                                                commres.last_modified as commres_last_modified, 
                                                extdev.last_modified as extdev_last_modified, 
                                                commcont.last_modified as commcont_last_modified, 
                                                ppacc.last_modified as ppacc_last_modified
                                                from Refill.commission_receivers commres 
                                                JOIN Refill.extdev_devices extdev ON (extdev.owner_key = commres.receiver_key) 
                                                JOIN Refill.commission_contracts commcont ON (commcont.contract_key=commres.contract_key)  
                                                JOIN Refill.reseller_types restype ON (restype.type_key=commres.type_key)  
                                                LEFT JOIN Refill.reseller_hierarchy reshier ON (commres.receiver_key=reshier.child_key)  
                                                LEFT JOIN Refill.commission_receivers comrcv ON (comrcv.receiver_key=reshier.parent_key) 
                                                JOIN Refill.pay_prereg_accounts ppacc ON (ppacc.owner_key=commres.receiver_key) 
                                                WHERE commres.last_modified >= ?
												OR extdev.last_modified >= ?
												OR commcont.last_modified >= ?
												OR ppacc.last_modified >=  ?
                                                
											"""
	
	@Transactional
	@Scheduled(cron = '${StdBalanceReportInformationAggregator.cron:0 30 * * * ?}')
	public void aggregate(){
		
		log.info("Started StdBalanceReportInformationAggregator aggregation ...")
		def date = getDate()
		
		def resellers = refill.query(RESELLERBALANCEREPORTSQL, [setValues: { ps ->
				use(TimeCategory) {
					ps.setTimestamp(1, toSqlTimestamp(date))
					ps.setTimestamp(2, toSqlTimestamp(date))
					ps.setTimestamp(3, toSqlTimestamp(date))
					ps.setTimestamp(4, toSqlTimestamp(date))
				}
			}] as PreparedStatementSetter, new ColumnMapRowMapper())
		
		log.debug("Got StdBalanceReportInformationAggregator ${resellers.size()}  from refill. and resellers are ${resellers}")
		if(resellers) {
			log.info("reseller exits")
		 	def sql = """
		 	INSERT INTO ${TABLE} 
		 	(resellerId,resellerName,msisdn,resellerParent,resellerStatus,resellerType,currentBalance,accountId,resellerPath)
		 	VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) 
		 	ON DUPLICATE KEY UPDATE
		 	resellerName=VALUES(resellerName),
			msisdn=VALUES(msisdn),
		 	resellerParent=VALUES(resellerParent),
		 	resellerStatus=VALUES(resellerStatus),
            resellerType=VALUES(resellerType),
            accountId=VALUES(accountId),
            resellerPath=VALUES(resellerPath)
		 	"""
		 	log.debug("sql================"+sql)
           
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
					
					def lastModifiedDates = [resellers[i].commres_last_modified ,
											 resellers[i].extdev_last_modified,
											 resellers[i].commcont_last_modified,
											 resellers[i].ppacc_last_modified,
											 date ].findAll().sort()
					
					
					date = lastModifiedDates.last()
					
				},
				getBatchSize: { resellers.size() }
				] as BatchPreparedStatementSetter)
			log.debug("Data inserted in StdBalanceReportInformationAggregator")
		}
		//update cursor with the modified cursor date
			
		updateCursor(date.format(dateFormat))	
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

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
 * Aggregates audit log detail informational.action_date, ifnull(aa.action,'not defined'), iu.UserId, al.description, al.action_date, al.action_key, al.user_key, al.channel_key
 * and update cursor after any action date change occurred
 * It dump data to `dataaggregator`.`std_audit_report_aggregation`
 * @author Saira Arif
 */
@Log4j
@DynamicMixin
public class StdAuditLogInformationAggregator extends AbstractAggregator
{
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
	static final def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
	
	static final def TABLE = "std_audit_report_aggregation"
	
	static final def AUDITLOGINFORMATIONSQL ="""
											SELECT al.audit_entry_key AS auditEntryKey, al.action_date AS actionDate, ifnull(aa.action,'not defined') AS action, 
											iu.UserId, al.description, al.action_key AS actionKey, al.user_key AS userKey, al.channel_key AS channelKey
											FROM id_audit_log al
											LEFT JOIN id_audit_actions aa ON al.action_key=aa.action_key 
											LEFT JOIN id_users iu ON iu.UserKey=al.user_key
											LEFT JOIN id_audit_channels ac ON al.channel_key=ac.channel_key
											WHERE al.channel_key in (0,2) and
											al.action_date > ?
											"""
	
	@Transactional
	@Scheduled(cron = '${StdAuditLogInformationAggregator.cron:0/1 * * * * ?}')
	public void aggregate(){
		
		log.info("Started StdAuditLogInformationAggregator aggregation ...")
		def date = getDate()	
		log.info(" cursor date:"+date)
		def auditLogResults = refill.query(AUDITLOGINFORMATIONSQL, [setValues: { ps ->
				use(TimeCategory) {
					ps.setTimestamp(1, toSqlTimestamp(date))
			}
			}] as PreparedStatementSetter, new ColumnMapRowMapper())
		
		log.info("Got StdAuditLogInformationAggregator ${auditLogResults.size()}  from refill.")
		if(auditLogResults) {
			
			 def sql = """
		 	INSERT INTO ${TABLE} 
		 	(auditEntryKey,actionDate,action,userId,description,actionKey,userKey,channelKey)
		 	VALUES (?, ?, ?, ?, ?, ?, ?, ?) 
		 	ON DUPLICATE KEY UPDATE
			actionDate=VALUES(actionDate),
			action=VALUES(action),
		 	userId=VALUES(userId),
			 description=VALUES(description),
            actionKey=VALUES(actionKey),
            userKey=VALUES(userKey),
            channelKey=VALUES(channelKey)
		 	"""
		  
			jdbcTemplate.batchUpdate(sql,[
				setValues: { ps, i ->
					ps.setInt(1,auditLogResults[i].auditEntryKey.hashCode())
					ps.setTimestamp(2,toSqlTimestamp(auditLogResults[i].actionDate))
					ps.setString(3,auditLogResults[i].action)
					ps.setString(4,auditLogResults[i].UserId)
					ps.setString(5,auditLogResults[i].description)
					ps.setString(6,auditLogResults[i].actionKey+"")
					ps.setString(7,auditLogResults[i].userKey+"")
					ps.setString(8,auditLogResults[i].channelKey+"")
					def lastModifiedDates = [auditLogResults[i].action_date , date].findAll().sort()
					date = lastModifiedDates.last()
				},
				getBatchSize: { auditLogResults.size() }
				] as BatchPreparedStatementSetter)
			
		}
		//update cursor with the modified cursor date
		updateCursor(date.format(dateFormat))
		log.debug("Updated cursor date:"+date.format(dateFormat))
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
				date = toSqlTimestamp(date - 90.days)
			}
		}
		return date
	}
	
}

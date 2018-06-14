package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter

import java.math.BigDecimal;
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
 *
 * @author Mohua Saha
 */
@Log4j
@DynamicMixin
public class StdNewActivationAggregator extends AbstractAggregator
{
	static final def TABLE = "std_new_activation_aggregation"
	//static final def RESELLERACTIVATIONSQL="select commres.tag as reseller_id,extdev.address as MSISDN,commres.name as reseller_name,restype.name as reseller_type,comrcv.name as reseller_parentname,extdev.time_activated as date_of_activation from Refill.commission_receivers commres JOIN Refill.extdev_history exthist ON (exthist.owner_key = commres.receiver_key)JOIN Refill.extdev_devices extdev ON (extdev.device_key=exthist.device_key)JOIN Refill.commission_contracts commcont ON (commcont.contract_key=commres.contract_key)JOIN Refill.reseller_types restype ON (restype.type_key=commcont.reseller_type_key)JOIN Refill.reseller_hierarchy reshier ON (reshier.child_key=commres.receiver_key)JOIN Refill.commission_receivers comrcv ON (comrcv.receiver_key=reshier.parent_key)"
	//static final def RESELLERACTIVATIONSQL = "SELECT commres.tag as reseller_id,extdev.address as MSISDN,commres.name as reseller_name, restype.name as reseller_type,comrcv.name as reseller_parentname, extdev.time_activated as date_of_activation from Refill.commission_receivers commres JOIN Refill.extdev_devices extdev ON (extdev.owner_key = commres.receiver_key) JOIN Refill.commission_contracts commcont ON (commcont.contract_key=commres.contract_key) JOIN Refill.reseller_types restype ON (restype.type_key=commcont.reseller_type_key) JOIN Refill.reseller_hierarchy reshier ON (reshier.child_key=commres.receiver_key) JOIN Refill.commission_receivers comrcv ON (comrcv.receiver_key=reshier.parent_key) WHERE commres.status=0"
	static final def RESELLERACTIVATIONSQL ="SELECT commres.tag as reseller_id,extdev.address as MSISDN,commres.name as reseller_name, restype.name as reseller_type,comrcv.name as reseller_parentname, extdev.time_activated as date_of_activation from Refill.commission_receivers commres JOIN Refill.extdev_devices extdev ON (extdev.owner_key = commres.receiver_key) JOIN Refill.commission_contracts commcont ON (commcont.contract_key=commres.contract_key) JOIN Refill.reseller_types restype ON (restype.type_key=commcont.reseller_type_key) JOIN Refill.reseller_hierarchy reshier ON (reshier.child_key=commres.receiver_key) JOIN Refill.commission_receivers comrcv ON (comrcv.receiver_key=reshier.parent_key) WHERE YEAR(extdev.time_activated) <> '1970'"
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
	
	
	@Transactional
	@Scheduled(cron = '${StdNewActivationAggregator.cron:0 0/30 * * * ?}')
	
	public void aggregate(){
		def resellers = refill.query(RESELLERACTIVATIONSQL,new ColumnMapRowMapper())
		
		log.info("Got StdNewActivationAggregatio ${resellers.size()} activate reseller from refill.")
		if(resellers) {log.info("reseller exits")
			jdbcTemplate.batchUpdate("insert into "+TABLE+" (resellerId,msisdn,resellerName,resellerType,resellerParent,activationDate) values (?,?,?,?,?,?) ON DUPLICATE KEY UPDATE activationDate=VALUES(activationDate),resellerName=VALUES(resellerName),resellerParent=Values(resellerParent)",[
				setValues: { ps, i ->
					ps.setString(1,resellers[i].reseller_id)
					ps.setString(2,resellers[i].MSISDN)
					ps.setString(3,resellers[i].reseller_name)
					ps.setString(4,resellers[i].reseller_type)
					ps.setString(5,resellers[i].reseller_parentname)
					ps.setTimestamp(6, toSqlTimestamp(resellers[i].date_of_activation))
				},
			getBatchSize: { resellers.size() }
				] as BatchPreparedStatementSetter)
			log.info("Data inserted in StdNewActivationAggregatio")
		}
			
	}
	
}
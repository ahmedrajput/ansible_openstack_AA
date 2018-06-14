package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.util.logging.Log4j

import java.util.concurrent.TimeUnit

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.PreparedStatementSetter

/**
 * Parent reseller MSISDN refer to the actual parent (not sender MSISDN)
 *
 * All informations are based on Refill.commission_receivers
 * and other tables from Refill database.
 *
 * @author Mohua Saha
 */

@Log4j
@DynamicMixin
public class StdAlertAppActualParentResellerAggregator extends AbstractAggregator
{

 static final def TABLE = "std_parent_reseller_aggregation"

 //static final def PARENT_RESELLER_SQL="SELECT now() AS aggregationTimestamp,extdev.address AS resellerMSISDN,extdevp.address AS parentResellerMSISDN,cont.email AS Email FROM Refill.commission_receivers commres JOIN Refill.extdev_devices extdev ON (extdev.owner_key = commres.receiver_key) JOIN Refill.reseller_hierarchy reshier ON (reshier.child_key=commres.receiver_key) JOIN Refill.extdev_devices extdevp ON (extdevp.owner_key =reshier.parent_key) LEFT JOIN Refill.catalogue_contacts cont ON (commres.address_key=cont.owner_key)"
 static final def PARENT_RESELLER_SQL="SELECT now() AS aggregationTimestamp,extdevp.address AS parentResellerMSISDN,extdev.address AS resellerMSISDN,cont.email AS parentEmail FROM Refill.commission_receivers commres JOIN Refill.reseller_hierarchy reshier ON (reshier.parent_key=commres.receiver_key) JOIN Refill.extdev_devices extdevp ON (extdevp.owner_key = commres.receiver_key) JOIN Refill.extdev_devices extdev ON (extdev.owner_key = reshier.child_key) LEFT JOIN Refill.catalogue_contacts cont ON (commres.address_key=cont.owner_key)"
 @Autowired
 @Qualifier("refill")
 private JdbcTemplate refill

 @Transactional
 @Scheduled(cron = '${StdAlertAppActualParentResellerAggregator.cron}')

 public void aggregate(){

		def resellers = refill.query(PARENT_RESELLER_SQL,new ColumnMapRowMapper())

		log.info("Got StdAlertAppActualParentResellerAggregation ${resellers.size()} from refill.")

		if(resellers) {

			log.info("reseller exits")

			jdbcTemplate.batchUpdate("insert into "+TABLE+" (aggregationTimestamp,parentResellerMSISDN,resellerMSISDN,parentEmail) values (?,?,?,?) ON DUPLICATE KEY UPDATE aggregationTimestamp=VALUES(aggregationTimestamp),parentResellerMSISDN=VALUES(parentResellerMSISDN),parentEmail=VALUES(parentEmail)",[
				setValues: { ps, i ->
					ps.setTimestamp(1,resellers[i].aggregationTimestamp)
					ps.setString(2,resellers[i].parentResellerMSISDN)
					ps.setString(3,resellers[i].resellerMSISDN)
					ps.setString(4,resellers[i].parentEmail)
				},
			getBatchSize: { resellers.size() }
				] as BatchPreparedStatementSetter)
			log.info("Data inserted in StdAlertAppActualParentResellerAggregation")
		}

 	}


}
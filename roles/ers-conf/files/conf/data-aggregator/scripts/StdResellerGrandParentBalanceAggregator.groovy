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
 * Aggregates reseller  and parent and grand parent detail information id,name,msisdn,parent,status,path & accountId
 * and update cursor after any change occurred
 * It dump data to `dataaggregator`.`std_reseller_grand_parent_aggregation`
 * @author Bilal.Ilyas
 */
@Log4j
@DynamicMixin
public class StdResellerGrandParentBalanceAggregator extends AbstractAggregator
{
	@Autowired
	@Qualifier("refill")
	private JdbcTemplate refill
		
	static final def dateFormat = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"
	
	static final def TABLE = "std_reseller_grand_parent_aggregation"
	
	static final def RESELLERBALANCEREPORTSQL ="""
											 SELECT res.tag as reseller_tag,SUBSTRING_INDEX(SUBSTRING_INDEX(res.reseller_path,'/',-1),'/',1) as reseller_id,
res.name as reseller_name,
extdev.address as MSISDN,type.id as reseller_type_id,type.name as reseller_type_name,
res.time_first_terminal_activation as reseller_date_activation,
res.time_created as reseller_date_creation,
(case res.status when '0' then 'Active' when '1' then 'InActive' when '2' then 'Blocked' when '3' then 'Frozen' else 'Disabled' end) as reseller_status,
account.balance as reseller_balance,
addr.street AS address_street, +addr.zip AS address_zip,
addr.city AS address_city,
country.name AS address_country,
account.accountId as account_id,
res.reseller_path as reseller_path,
account.currency as currency,
(case SUBSTRING_INDEX(SUBSTRING_INDEX(res.reseller_path,'/',-1),'/',1) when SUBSTRING_INDEX(SUBSTRING_INDEX(res.reseller_path,'/',-2),'/',1) then null else SUBSTRING_INDEX(SUBSTRING_INDEX(res.reseller_path,'/',-2),'/',1) end) as reseller_parent_id,
(case SUBSTRING_INDEX(SUBSTRING_INDEX(res.reseller_path,'/',-2),'/',1) when SUBSTRING_INDEX(SUBSTRING_INDEX(res.reseller_path,'/',-3),'/',1) then null else SUBSTRING_INDEX(SUBSTRING_INDEX(res.reseller_path,'/',-3),'/',1) end) as reseller_grand_parent_id 
FROM Refill.commission_receivers res
LEFT JOIN Refill.reseller_types `type` ON type.type_key = res.type_key
LEFT JOIN Refill.catalogue_addresses addr ON res.address_key=addr.owner_key
LEFT JOIN Refill.extdev_devices extdev ON (extdev.owner_key = res.receiver_key AND extdev.state!=9)
LEFT JOIN accounts.accounts account ON account.accountId=res.tag
LEFT JOIN Refill.loc_countries `country` ON (country.country_key=res.country_key)
"""
	
	@Transactional
	@Scheduled(cron = '${StdResellerGrandParentBalanceAggregator.cron:0 0 * * * ?}')
	public void aggregate(){
		
		log.info("Started StdResellerGrandParentBalanceAggregator aggregation ...")
		def date = getDate()
		
		def resellers = refill.query(RESELLERBALANCEREPORTSQL, [setValues: { ps ->
			use(TimeCategory) {
				
							
				}				
			}] as PreparedStatementSetter, new ColumnMapRowMapper())
		def parentResellers=[]
		def grandParentResellers=[]
		log.debug("Got StdResellerGrandParentBalanceAggregator ${resellers.size()}  from refill. and resellers are ${resellers}")
		
		def mapResellers=[:]
		def mapParentResellers=[:]
		def mapGrandParentResellers=[:]
		
		log.info("Start Prepareing Reseller map.")
		resellers.eachWithIndex { resellerRow, index ->		
			mapResellers[resellerRow.reseller_id]=resellerRow
		}
		log.info("End Prepareing Reseller map.${mapResellers.size()}")		
		

		log.info("START Parsing Parent and GrandParent Resellers")
		
		for(int i;i<resellers.size();i++){
			
			if(resellers[i].reseller_parent_id){
				def resellerParent = resellers[i].reseller_parent_id;
				mapParentResellers[resellerParent] = mapResellers[resellerParent]
			}
             
            if(resellers[i].reseller_grand_parent_id){
				def resellerGrandParent = resellers[i].reseller_grand_parent_id;
				mapGrandParentResellers[resellerGrandParent]=mapResellers[resellerGrandParent]				
			}
            
		}//end for loop
		
		log.info("No of Parent Resellers  ${mapParentResellers.size()} ") 
		log.info("No of Grand Parent Resellers ${mapGrandParentResellers.size()}")
		
		log.info("END Parsing")
		
		if(resellers) {
			log.info("Resellers Exits and Ready to add in DB")
			 def sql = """
		 	INSERT INTO ${TABLE} 
            (reseller_tag,resellerId, resellerName, msisdn, resellerTypeId,resellerTypeName, activationDate, creationDate, resellerStatus, currentBalance, address, city, state, accountId, resellerPath, currency, p_resellerId, g_p_resellerId,
			p_resellerName ,p_msisdn,p_resellerTypeId,p_resellerTypeName,
			p_activationDate,p_creationDate,p_resellerStatus,p_currentBalance,p_address,p_city,p_state,
			g_p_resellerName,g_p_msisdn,g_p_resellerTypeId,g_p_resellerTypeName,
			g_p_activationDate,g_p_creationDate,g_p_resellerStatus,g_p_currentBalance,g_p_address,g_p_city,g_p_state)
		 	VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) 
		 	ON DUPLICATE KEY UPDATE
			reseller_tag=VALUES(reseller_tag), 
		 	resellerName=VALUES(resellerName),
			msisdn=VALUES(msisdn),
		 	p_resellerId=VALUES(p_resellerId),
			g_p_resellerId=VALUES(g_p_resellerId),
		 	resellerStatus=VALUES(resellerStatus),
            resellerTypeId=VALUES(resellerTypeId),
			resellerTypeName=VALUES(resellerTypeName),
            accountId=VALUES(accountId),
            resellerPath=VALUES(resellerPath),
            currentBalance=VALUES(currentBalance),
			activationDate=VALUES(activationDate),resellerStatus = VALUES(resellerStatus),
			address=VALUES(address),city = VALUES(city),state= VALUES(state),
			p_resellerName=VALUES(p_resellerName) ,p_msisdn=VALUES(p_msisdn),p_resellerTypeId=VALUES(p_resellerTypeId),p_resellerTypeName=VALUES(p_resellerTypeName),
			p_activationDate=VALUES(p_activationDate),p_creationDate=VALUES(p_creationDate),p_resellerStatus=VALUES(p_resellerStatus),p_currentBalance=VALUES(p_currentBalance),p_address=VALUES(p_address),p_city=VALUES(p_city),p_state=VALUES(p_state),
			g_p_resellerName=VALUES(g_p_resellerName),g_p_msisdn=VALUES(g_p_msisdn),g_p_resellerTypeId=VALUES(g_p_resellerTypeId),g_p_resellerTypeName=VALUES(g_p_resellerTypeName),
			g_p_activationDate=VALUES(g_p_activationDate),g_p_creationDate=VALUES(g_p_creationDate),g_p_resellerStatus=VALUES(g_p_resellerStatus),g_p_currentBalance=VALUES(g_p_currentBalance),g_p_address=VALUES(g_p_address),g_p_city=VALUES(g_p_city),g_p_state=VALUES(g_p_state)

		 	"""
			log.debug("SQL:[${sql}]")
		    java.sql.Time
			jdbcTemplate.batchUpdate(sql,[
				setValues: { ps, i ->
					ps.setString(1,resellers[i].reseller_tag)
					ps.setString(2,resellers[i].reseller_id)
					ps.setString(3,resellers[i].reseller_name)
					ps.setString(4,resellers[i].MSISDN)
					ps.setString(5,resellers[i].reseller_type_id)
					ps.setString(6,resellers[i].reseller_type_name)
					ps.setTimestamp(7,toSqlTimestamp(resellers[i].reseller_date_activation))
					ps.setTimestamp(8,toSqlTimestamp(resellers[i].reseller_date_creation))
					ps.setString(9,resellers[i].reseller_status)
					ps.setString(10,resellers[i].reseller_balance.toString())
					ps.setString(11,resellers[i].address_street)
					ps.setString(12,resellers[i].address_city)
					ps.setString(13,resellers[i].address_country)
					ps.setString(14,resellers[i].account_id)
					ps.setString(15,resellers[i].reseller_path.toString())
					ps.setString(16,resellers[i].currency)
					ps.setString(17,resellers[i].reseller_parent_id)
					ps.setString(18,resellers[i].reseller_grand_parent_id)
					
					if(resellers[i].reseller_parent_id){
						ps.setString(19,mapParentResellers[resellers[i].reseller_parent_id].reseller_name)
						ps.setString(20,mapParentResellers[resellers[i].reseller_parent_id].MSISDN)
						ps.setString(21,mapParentResellers[resellers[i].reseller_parent_id].reseller_type_id)
						ps.setString(22,mapParentResellers[resellers[i].reseller_parent_id].reseller_type_name)
						ps.setTimestamp(23,toSqlTimestamp(mapParentResellers[resellers[i].reseller_parent_id].reseller_date_activation))
						ps.setTimestamp(24,toSqlTimestamp(mapParentResellers[resellers[i].reseller_parent_id].reseller_date_creation))
						ps.setString(25,mapParentResellers[resellers[i].reseller_parent_id].reseller_status)
						ps.setString(26,mapParentResellers[resellers[i].reseller_parent_id].reseller_balance.toString())
						ps.setString(27,mapParentResellers[resellers[i].reseller_parent_id].address_street)
						ps.setString(28,mapParentResellers[resellers[i].reseller_parent_id].address_city)
						ps.setString(29,mapParentResellers[resellers[i].reseller_parent_id].address_country)
						
					}else{
					
						ps.setString(19,null)
						ps.setString(20,null)
						ps.setString(21,null)
						ps.setString(22,null)
						ps.setTimestamp(23,null)
						ps.setTimestamp(24,null)
						ps.setString(25,null)
						ps.setString(26,null)
						ps.setString(27,null)
						ps.setString(28,null)
						ps.setString(29,null)
					}
					
					if(resellers[i].reseller_grand_parent_id){
						ps.setString(30,mapGrandParentResellers[resellers[i].reseller_grand_parent_id].reseller_name)
						ps.setString(31,mapGrandParentResellers[resellers[i].reseller_grand_parent_id].MSISDN)
						ps.setString(32,mapGrandParentResellers[resellers[i].reseller_grand_parent_id].reseller_type_id)
						ps.setString(33,mapGrandParentResellers[resellers[i].reseller_grand_parent_id].reseller_type_name)
						ps.setTimestamp(34,toSqlTimestamp(mapGrandParentResellers[resellers[i].reseller_grand_parent_id].reseller_date_activation))
						ps.setTimestamp(35,toSqlTimestamp(mapGrandParentResellers[resellers[i].reseller_grand_parent_id].reseller_date_creation))
						ps.setString(36,mapGrandParentResellers[resellers[i].reseller_grand_parent_id].reseller_status)
						ps.setString(37,mapGrandParentResellers[resellers[i].reseller_grand_parent_id].reseller_balance.toString())
						ps.setString(38,mapGrandParentResellers[resellers[i].reseller_grand_parent_id].address_street)
						ps.setString(39,mapGrandParentResellers[resellers[i].reseller_grand_parent_id].address_city)
						ps.setString(40,mapGrandParentResellers[resellers[i].reseller_grand_parent_id].address_country)
					}else{						
						ps.setString(30,null)
						ps.setString(31,null)
						ps.setString(32,null)
						ps.setString(33,null)
						ps.setTimestamp(34,null)
						ps.setTimestamp(35,null)
						ps.setString(36,null)
						ps.setString(37,null)
						ps.setString(38,null)
						ps.setString(39,null)
						ps.setString(40,null)
					}
					
				},
				getBatchSize: { resellers.size() }
				] as BatchPreparedStatementSetter)					
		}
		
		log.info("Data inserted in StdResellerGrandParentBalanceAggregator")
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
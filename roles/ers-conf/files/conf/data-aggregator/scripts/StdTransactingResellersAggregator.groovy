package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import java.util.concurrent.TimeUnit
import java.text.SimpleDateFormat;
import java.util.Date;
import java.sql.*

import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ColumnMapRowMapper
import org.springframework.jdbc.core.PreparedStatementSetter


/**
 * Aggregates details of Transacting Resellers.
 * Used by reports:
 * <ul>
 * <li></li>
 * </ul>
 *
 * @author Muhammad Asad Ullah Khan
 */
@Log4j
@DynamicMixin
public class StdTransactingResellersAggregator extends AbstractAggregator
{
    static final def TABLE = "std_transacting_resellers_aggregation"
   
    @Value('${StdTransactingResellersAggregator.batch:1000}')
    int limit
	
	@Value('${StdTransactingResellersAggregator.allowed_profiles:DATA_BUNDLE,TOPUP,CREDIT_TRANSFER,REVERSE_CREDIT_TRANSFER,REVERSE_TOPUP,VOS_PURCHASE,VOT_PURCHASE,VOUCHER_REDEEM,REVERSE_VOS_PURCHASE,REVERSE_VOT_PURCHASE,REVERSE_VOT_PURCHASE,PRODUCT_RECHARGE,PURCHASE,REVERSAL,MM2ERS}')
	String allowed_profiles
	
	def allowedProfiles = []
	
	def reseller_receiving_balance = []
	
	Map<String,Map<String,String>> senderList = new HashMap<String,Map<String,String>>();
	
	Map<String,String> senderTransactionDetails = new HashMap<>();
	
	Map<String,Map<String,String>> receiverList = new HashMap<>();
	
	Map<String,String> receiverTransactionDetails = new HashMap<>();
	
	Date currentdate = new Date().clone().clearTime();

    @EqualsAndHashCode
    @ToString
    private static class Key
    {
        String ersReference
        Date date
		Date lastTransactionDate
        String resellerId
        String receiverId
		String receiverResellerId

        String transactionType
		String transactionResult
		BigDecimal transactionAmount
		
        BigDecimal resellerOpeningBalance
		BigDecimal resellerClosingBalance
		
		BigDecimal receiverClosingBalance
		BigDecimal receiverOpeningBalance

    }
    @Transactional
    @Scheduled(cron = '${StdTransactingResellersAggregator.cron:0 0/30 * * * ?}')
    public void aggregate()
    {
		if(allowed_profiles){
			def allowedProfile = allowed_profiles.split(",")
			for (resellerProfile in allowedProfile) {
					allowedProfiles.add(resellerProfile.asType(String))
			}
		}
		
        def transactions = getTransactions(limit)
        if (transactions)
        {
            def aggregation = findAllowedProfiles(transactions, allowedProfiles)
                    .findAll(successfulTransactions)
                    .collect(transactionInfo)
                    .findAll({it.resellerId != null})
                    .groupBy(key)
                    .collect(statistics)
            updateAggregation(aggregation)
			updateTotalRceivingBalanceAggregation(reseller_receiving_balance)
			if(receiverList.size()!=0 && receiverTransactionDetails.size()!=0)
			{
				maintainResellersBalances(senderList,senderTransactionDetails,receiverList,receiverTransactionDetails);
			}
			updateResellerBalances()
			reseller_receiving_balance = null
			reseller_receiving_balance = []
			
			senderList = new HashMap<>();
			senderTransactionDetails = new HashMap<>();
			receiverList = new HashMap<>();
			receiverTransactionDetails = new HashMap<>();
			
            updateCursor(transactions)
            schedule()
        }
    }

    private def transactionInfo =
            {
                log.debug("Processing transaction: ${it}")
                def tr = parser.parse(it.getJSON()).getAsJsonObject()
                log.debug("TransactionData --- "+tr)
                def profileId = it.profile

                def ersReference = asString(tr, "ersReference")
				
                def date = it.getStartTime().clone().clearTime()
				def lastTransactionDate = it.getEndTime()
				
				def transactionResult = tr.get("resultCode")?.getAsString()

                def senderData = findSenderPrincipalAlongWithTransactionRow(tr)
                def resellerId = asString(senderData.principal, "resellerId")
				def receiverId = getReceiverResellerId(tr)
				
				def transactionAmount = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "amount")?.abs()
               
				def receiverResellerId = getReceiverResellerId(tr)
				
				def channel = asString(tr, "channel")
				
                def transactionType = translatedTransactionProfile(channel, profileId)
				
                def resellerOpeningBalance = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "balanceBefore")
                def resellerClosingBalance = getBigDecimalFieldFromAnyField(senderData.transactionRow, "value", "balanceAfter")
				
				def receiverOpeningBalance = getReceiverBalanceBefore(tr)==null ? new BigDecimal(0) : getReceiverBalanceBefore(tr).toBigDecimal()
				def receiverClosingBalance = getReceiverBalance(tr)== null ? new BigDecimal(0) : getReceiverBalance(tr)?.toBigDecimal()

                [
                        ersReference          : ersReference,
						
						resellerId            : resellerId,
						
						receiverId       	 : receiverId,
						
                        date                  : date,
						lastTransactionDate   : lastTransactionDate,
                        
                        transactionType       : transactionType,
						transactionResult     : transactionResult,
						transactionAmount     : transactionAmount,
						
                        resellerOpeningBalance: resellerOpeningBalance,
                        resellerClosingBalance: resellerClosingBalance,
						
						receiverOpeningBalance: receiverOpeningBalance,
						receiverClosingBalance: receiverClosingBalance,
						
                ]
            }

    private def key =
            {
                log.debug("Creating key from ${it}")
                [
                        ersReference          : it.ersReference,
						
						resellerId            : it.resellerId,
						
						receiverId        	  : it.receiverId,
						
						transactionType       : it.transactionType,
						transactionResult     : it.transactionResult,
						transactionAmount     : it.transactionAmount,
						
						resellerOpeningBalance: it.resellerOpeningBalance,
						resellerClosingBalance: it.resellerClosingBalance,
						
						receiverOpeningBalance: it.receiverOpeningBalance,
						receiverClosingBalance: it.receiverClosingBalance,
						
                        date                  : it.date,
						lastTransactionDate   : it.lastTransactionDate					    
                ]
            }

    private def statistics =
            {	key, list ->
                [
                        key             : key
                ]
            }

    private def updateAggregation(List aggregation)
    {
        log.info("Aggregated into ${aggregation.size()} rows.")
        if(aggregation)
        {
			senderList.clear();
			receiverList.clear();
			senderTransactionDetails.clear();
			receiverTransactionDetails.clear();
			
            def sql = "INSERT INTO ${TABLE} (reseller_id,reseller_balance,reseller_balance_out,last_transaction_date,date,total_reseller_balance_out,aggregation_time, transaction_Type) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE reseller_balance_out = reseller_balance_out + VALUES(reseller_balance_out), reseller_balance = VALUES(reseller_balance),last_transaction_date =(CASE VALUES(transaction_Type) WHEN 'TOPUP' THEN VALUES(last_transaction_date) WHEN 'PURCHASE' THEN VALUES(last_transaction_date) WHEN 'DATA_BNDLE' THEN VALUES(last_transaction_date) ELSE last_transaction_date END), total_reseller_balance_out = total_reseller_balance_out + VALUES(total_reseller_balance_out),transaction_type =(CASE VALUES(transaction_Type) WHEN 'TOPUP' THEN VALUES(transaction_Type) WHEN 'PURCHASE' THEN VALUES(transaction_Type) WHEN 'DATA_BNDLE' THEN VALUES(transaction_Type) ELSE transaction_Type END)"
			
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues:
                            { ps, i ->
                                def row = aggregation[i]
                                int index=0
								ps.setString(++index,row.key.resellerId)
                                ps.setBigDecimal(++index,row.key.resellerClosingBalance)
								
								if(row.key.transactionType.equals("TOPUP") || row.key.transactionType.equals("PURCHASE") || row.key.transactionType.equals("DATA_BUNDLE"))
								{
									ps.setBigDecimal(++index, row.key.transactionAmount)
									ps.setTimestamp(++index, toSqlTimestamp(row.key.lastTransactionDate))
								}
								else
								{
									ps.setBigDecimal(++index,  new BigDecimal("0".replaceAll(",", "")))
									ps.setTimestamp(++index, null)
								}
								
								ps.setString(++index,toSqlDate(row.key.date).toString())
								
								ps.setBigDecimal(++index, row.key.transactionAmount)
								
								ps.setDate(++index,toSqlDate(currentdate))
								
								ps.setString(++index,row.key.transactionType)
								
								String senderDateAndBalance = row.key.resellerClosingBalance+","+toSqlTimestamp(row.key.lastTransactionDate).toString();
								senderTransactionDetails.put(row.key.resellerId, senderDateAndBalance);
								senderList.put(toSqlDate(row.key.date).toString(), senderTransactionDetails);
								
								if(row.key.transactionType.equals("CREDIT_TRANSFER") || row.key.transactionType.equals("SUPPORT_TRANSFER") || row.key.transactionType.equals("MM2ERS"))
								{ 										
									String receiverDateAndBalance = row.key.receiverClosingBalance+","+toSqlTimestamp(row.key.lastTransactionDate).toString();
									receiverTransactionDetails.put(row.key.receiverId, receiverDateAndBalance);
									receiverList.put(toSqlDate(row.key.date).toString(), receiverTransactionDetails);
									reseller_receiving_balance.add(row.key.receiverId+","+row.key.receiverClosingBalance+","+row.key.transactionAmount+","+toSqlDate(row.key.date).toString())
								}
                                
                            },
                    getBatchSize:
                            { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
	
	private def updateTotalRceivingBalanceAggregation(List reseller_receiving_balance)
	{
		if(reseller_receiving_balance)
		{
			def sql = "INSERT INTO ${TABLE} (reseller_id,reseller_balance,reseller_balance_in,date,aggregation_time) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE reseller_balance_in = reseller_balance_in + VALUES(reseller_balance_in), reseller_balance = VALUES(reseller_balance)"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
					setValues:
							{ ps, i ->
								def row = reseller_receiving_balance[i]
								def receiverId
								def receiverBalance
								def receiverAmount
								def receiverDate
							
								String[] reseller_receiving_balance_array
								reseller_receiving_balance_array = row.split(',');
		
								if(reseller_receiving_balance_array.size() == 4)
								{
									receiverId = reseller_receiving_balance_array[0]
								    receiverBalance = reseller_receiving_balance_array[1]
									receiverAmount = reseller_receiving_balance_array[2]
									receiverDate = reseller_receiving_balance_array[3]
								}
								
								int index=0
								ps.setString(++index, receiverId)
								ps.setBigDecimal(++index,new BigDecimal(receiverBalance.replaceAll(",", "")))
								ps.setBigDecimal(++index,new BigDecimal(receiverAmount.replaceAll(",", "")))
								ps.setString(++index,receiverDate)
								ps.setDate(++index,toSqlDate(currentdate))
								
							},
					getBatchSize:
							{ reseller_receiving_balance.size() }
			] as BatchPreparedStatementSetter)
		}
	}
	
	public void maintainResellersBalances(Map<String,Map<String,String>> senderList,Map<String,String> senderTransactionDetails,Map<String,Map<String,String>> receiverList,Map<String,String> receiverTransactionDetails)
	{
		ArrayList<String> resellersBalanceDetailArray = new ArrayList<String>();
		
		log.info("senderRsellerList: "+senderList);
		log.info("receiverResellerList: "+receiverList);
		
		for (Map.Entry<String,Map<String,String>> receiverMapEntry : receiverList.entrySet())
		{
			if(receiverList.get(receiverMapEntry.getKey())!=null && senderList.get(receiverMapEntry.getKey())!=null)
			{
				Map<String,String> receiverTransactionDetailsTemp = receiverList.get(receiverMapEntry.getKey());
				Map<String,String> senderTransactionDetailsTemp = senderList.get(receiverMapEntry.getKey());
					
				for (Map.Entry<String,String> receiverTransactionEntry : receiverTransactionDetailsTemp.entrySet())
				{
					if(senderTransactionDetailsTemp.containsKey(receiverTransactionEntry.getKey()))
					{
						String senderEntry = senderTransactionDetailsTemp.get(receiverTransactionEntry.getKey());
						String receiverEntry = receiverTransactionDetailsTemp.get(receiverTransactionEntry.getKey());	
						String senderBalanceString=senderEntry.substring(0,senderEntry.indexOf(',')).replaceAll(",","");
						BigDecimal senderBalance=new BigDecimal(senderBalanceString);
								
						String receiverBalanceString=receiverEntry.substring(0,receiverEntry.indexOf(',')).replaceAll(",","");
						BigDecimal receiverBalance=new BigDecimal(receiverBalanceString);
								
						String senderDateString=senderEntry.substring(senderEntry.indexOf(',')+1);
						String receiverDateString=receiverEntry.substring(receiverEntry.indexOf(',')+1);
						SimpleDateFormat formatter = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss.SSS");
						Date senderDate = null;
						Date receiverDate = null;
								
						try
						{
							senderDate = formatter.parse(senderDateString);
							receiverDate = formatter.parse(receiverDateString);
						}
						catch(Exception e)
						{
							log.info("Error Ocurred While Formating Date"+e);
							return;		
						}
						if(senderDate.after(receiverDate))
						{
							resellersBalanceDetailArray.add(receiverTransactionEntry.getKey()+","+senderBalance+","+receiverMapEntry.getKey());
						}
						else
						{
							resellersBalanceDetailArray.add(receiverTransactionEntry.getKey()+","+receiverBalance+","+receiverMapEntry.getKey());
						}
								
					}
							
				}
			}
		}
		
		log.info("Final Balance Array: "+resellersBalanceDetailArray);
		if(resellersBalanceDetailArray.size()>0)
		{
			maintainBlanaceInDb(resellersBalanceDetailArray);
		}
	}
	
	public void maintainBlanaceInDb(ArrayList balanceDetailAggregation)
	{
		if(balanceDetailAggregation)
		{
			def sql =  "UPDATE ${TABLE} SET reseller_balance = ? WHERE reseller_id = ? AND date = ?"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
					setValues:
							{ ps, i ->
								
								def row = balanceDetailAggregation[i]
								def resellerId
								def resellerBalance
								def resellerDate
								
								String[] reseller_balances__aggregation_array
								reseller_balances__aggregation_array = row.split(',');
		
								if(reseller_balances__aggregation_array.size() == 3)
								{
									resellerId = reseller_balances__aggregation_array[0]
									resellerBalance = reseller_balances__aggregation_array[1]
									resellerDate = reseller_balances__aggregation_array[2]
								}
								int index=0
								ps.setBigDecimal(++index,new BigDecimal(resellerBalance.replaceAll(",", "")))
								ps.setString(++index, resellerId)
								ps.setString(++index,resellerDate)
							},
					getBatchSize:
							{ balanceDetailAggregation.size() }
			] as BatchPreparedStatementSetter)
		}
	}
	
	public void updateResellerBalances()
	{	
		def selectResellerBalanceInfo = "SELECT t.reseller_id,t.date,t.reseller_balance,t.total_reseller_balance_out,t.reseller_balance_in FROM  dataaggregator.std_transacting_resellers_aggregation t Where aggregation_time=? "
		def balanceInfo = jdbcTemplate.query(selectResellerBalanceInfo,
				[
						setValues: { ps ->
							int index = 0
							ps.setDate(++index,toSqlDate(currentdate))
						}
				]as PreparedStatementSetter, new ColumnMapRowMapper()
		)
        log.info("balanceInfo: "+balanceInfo)
		reconcileBalance(balanceInfo)
	}
	
	public void reconcileBalance(ArrayList balanceInfo)
	{
		if(balanceInfo)
		{
			def sql =  "UPDATE ${TABLE} SET reseller_balance_before = ? WHERE reseller_id = ? AND date = ?"
			def batchUpdate = jdbcTemplate.batchUpdate(sql, [
					setValues:
							{ ps, i ->
								
								def row = balanceInfo[i]

								def resellerId = row.reseller_id
								def resellerBalance = row.reseller_balance
								def resellerDate =row.date
								def resellerBalanceOut = row.total_reseller_balance_out
								def resellerBalanceIn = row.reseller_balance_in
				
								int index=0
								if(resellerBalanceOut == null)
								{
									resellerBalanceOut = 0;
								}
								if(resellerBalanceIn == null)
								{
									resellerBalanceIn = 0;
								}
								ps.setBigDecimal(++index,resellerBalanceOut + resellerBalance - resellerBalanceIn)
								ps.setString(++index, resellerId)
								ps.setString(++index,resellerDate.toString())
								
							},
					getBatchSize:
							{ balanceInfo.size() }
			] as BatchPreparedStatementSetter)
		}
	}
}

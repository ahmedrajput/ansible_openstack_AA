package se.seamless.ers.components.dataaggregator.aggregator

import groovy.time.TimeCategory
import groovy.transform.EqualsAndHashCode
import groovy.transform.ToString
import groovy.util.logging.Log4j
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.transaction.annotation.Transactional
import se.seamless.ers.components.dataaggregator.txsource.Transaction


@Log4j
@DynamicMixin
public class StdTransactionDetailsStatisticAggregator extends AbstractAggregator {
    static final def TABLE = "std_transaction_details_statistic_aggregation"
    
    def allowedChannels = []

    @Value('${StdTransactionDetailsStatisticAggregator.batch:1000}')
    int limit
    
    @Value('${StdTransactionDetailsStatisticAggregator.allowed_channels:USSD,SMS,WEB,WEBSERVICE}')
	String allowed_channels

    @EqualsAndHashCode
    @ToString
    private static class Key
    {
        String ersReference
        Date date
        String transactionType
        String senderMSISDN
        String senderResellerID
        String receiverMSISDN
        String transactionAmount
        String channel
        String transactionStatus
        String resultDescription
        String momTransactionID
        String receiverResellerID
        String senderResellerTypeId
        String receiverResellerTypeId
        BigDecimal senderBalanceBefore
        BigDecimal senderBalanceAfter
        BigDecimal receiverBalanceBefore
        BigDecimal receiverBalanceAfter
        String hour
        int lessThanOne
        int greaterThanTen
		String accountLinkTypeId
    }
    
    def findAllowedChannel = 
		{	txTransactions, channels ->
				if(!txTransactions) 
				{
					new ArrayList<Transaction>();
				}
				else 
				{
					txTransactions.findAll
					{
						  it != null && channels.contains(asString(parser.parse(it.getJSON()).getAsJsonObject(),"channel"))
					}
				}
		}
    

    @Transactional
    @Scheduled(cron = '${StdTransactionDetailsStatisticAggregator.cron:0 0/2 * * * ?}')
    public void aggregate() 
    {
    	allowedChannels = []
		
		if(allowed_channels){
			def allowedChannelsList = allowed_channels.split(",")
			 for (allowedChannel in allowedChannelsList) {
					allowedChannels.add(allowedChannel.asType(String))
			}
		}
    
        def transactions = getTransactions(limit)
        //log.debug("transactions : "+transactions)
        if (transactions) 
        {
        	def trans =findAllowedChannel(transactions, allowedChannels)
        	
            def aggregation = trans
                    .collect(transactionInfo)
                    .groupBy(key)
                    .collect(statistics)
           
                    
            updateAggregation(aggregation)
            updateCursor(transactions)
            schedule()
        }
    }
    

    private def transactionInfo =
    {
        log.info("Processing transaction StdTransactionDetailsStatisticAggregator.batch: ${it}")
        def tr = parser.parse(it.getJSON()).getAsJsonObject()
        def transactionStatus = "FAILED"
        log.info("*************** transaction :" + tr + " **************")

        def resellerName=getSenderResellerName(tr)
        def senderResellerID= getSenderResellerId(tr)
        def receiverResellerID= getReceiverResellerId(tr)
        
        def senderMSISDN = getSenderMSISDN(tr)
        def transactionType = it.getProfile()?.equals("MOLLET_DEPOSIT") ? "CASHIN" : it.getProfile()
        log.info("*************** transactionType :" + transactionType + " **************")
        def channel = asString(tr, "channel")
        def resultCode = tr.get("resultCode")?.getAsInt()
    
        def transactionResult = tr.get("resultCode")?.getAsString()
        def resultDescription=tr.get("resultMessage")?.getAsString()
        
        def senderResellerTypeId = getSenderResellerTypeId(tr)
        def receiverResellerTypeId = getReceiverResellerTypeId(tr)
    
        def transactionAmount = getBigDecimalFieldFromAnyField(tr, "value", "requestedTransferAmount", "requestedTopupAmount")
        if (transactionAmount == 0){
                tr.get("transactionRows")?.getAsJsonArray()?.iterator().collect {
                transactionAmount = getBigDecimalFieldFromAnyField(it,"value","amount")
            }
        }
	def receiverMSISDN = getReceiverMSISDN(tr)

        def momTransactionID=tr.get("transactionProperties")?.getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("MOMTransactionID")?.getAsString();
    
        def senderBalanceBefore = getSenderBalanceBefore(tr) ==null ? BigDecimal.ZERO :getSenderBalanceBefore(tr).toBigDecimal()
        def senderBalanceAfter = getSenderBalanceAfter(tr)==null ? BigDecimal.ZERO : getSenderBalanceAfter(tr).toBigDecimal()
        def receiverBalanceBefore = getReceiverBalanceBefore(tr)==null ? BigDecimal.ZERO : getReceiverBalanceBefore(tr).toBigDecimal()
        def receiverBalanceAfter = getReceiverBalanceAfter(tr)== null ? BigDecimal.ZERO : getReceiverBalanceAfter(tr)?.toBigDecimal()
        
        def startTime = it.getStartTime()
        def hour = it.getStartTime()[Calendar.HOUR_OF_DAY]
        def endTime = it.getEndTime()
        def lessThanOne = 0
        def greaterThanTen = 0
		def principals
		def accountLinkTypeId
        def profileId = it.profile
		if (profileId == "PURCHASE")
		{
			principals = tr.get("receiverPrincipal")
			accountLinkTypeId = principals.findResult 
			{	principal ->
				principal.get("accounts")?.getAsJsonArray()?.iterator().findResult
				{	account ->
					account.get("accountFields").getAsJsonObject()?.get("map")?.getAsJsonObject()?.get("typeId")?.getAsString()
				}
			}
		}
        else if(profileId == "TOPUP"){
			accountLinkTypeId = it.getErsTransaction()?.getTransactionProperties()?.get("accountLinkTypeId")
		}
		else if(profileId == "VAS_BUNDLE"){
            accountLinkTypeId = it.getErsTransaction()?.getTransactionProperties()?.get("groupId")
            if(accountLinkTypeId == "Prepaid")
            {
                accountLinkTypeId = "PREPAID"
            }else{
                accountLinkTypeId = "POSTPAID"
            }
        }	
        
        use(groovy.time.TimeCategory)
        {
            def duration = endTime - startTime
            log.info("duration ::: ${duration}")
            def secondsDifference = duration.seconds
            log.info("Diff inSeconds: ${duration.seconds}, Hours: ${duration.hours}")
            if(secondsDifference > 10)
            {
                greaterThanTen = 1
            }
            else if(secondsDifference < 1)
            {
                lessThanOne = 1
            }
        }
    
        log.info("%%%%%%%%%%%%%% lessThanOne: ${lessThanOne}, greaterThanTen: ${greaterThanTen} %%%%%%%%%%%%%%%%")
    
        log.debug("transactionResult:"+ transactionResult)
    
        if(transactionResult == "0")
        {
            transactionStatus = "SUCCESS"
        }
        else if(transactionResult == "201")
        {
            transactionStatus = "PENDING"
        }
        else if(transactionResult == "100")
        {
            transactionStatus = "DENIED"
        }
        else if(transactionResult == "501")
        {
            transactionStatus = "FAILED"
        }
        
        [
                date                    : it.getStartTime(),
                transactionType         : transactionType,
                transactionAmount       : transactionAmount ,
                senderResellerID        : senderResellerID,
                senderMSISDN            : senderMSISDN,
                ersReference            : it.ersReference,
                transactionStatus       : transactionStatus,
                resultDescription       : resultDescription,
                channel                 : channel,
                receiverMSISDN          : receiverMSISDN,
                momTransactionID        : momTransactionID,
                receiverResellerID      : receiverResellerID,
                senderResellerTypeId    : senderResellerTypeId,
                receiverResellerTypeId  : receiverResellerTypeId,
                senderBalanceBefore     : senderBalanceBefore,
                senderBalanceAfter      : senderBalanceAfter,
                receiverBalanceBefore   : receiverBalanceBefore,
                receiverBalanceAfter    : receiverBalanceAfter,
                hour                    : hour,
                lessThanOne             : lessThanOne,
                greaterThanTen          : greaterThanTen,
				accountLinkTypeId	    : accountLinkTypeId
        ]
    }

    private def key =
    {
        log.debug("Creating key from ${it}");
        new Key
            (
                date: it.date,
                transactionType         : it.transactionType,
                transactionAmount       : it.transactionAmount,
                senderMSISDN            : it.senderMSISDN,
                ersReference            : it.ersReference,
                resultDescription       : it.resultDescription,
                receiverMSISDN          : it.receiverMSISDN,
                channel                 : it.channel,
                transactionStatus       : it.transactionStatus,
                senderResellerID        : it.senderResellerID,
                momTransactionID        : it.momTransactionID,
                receiverResellerID      : it.receiverResellerID,
                senderResellerTypeId    : it.senderResellerTypeId,
                receiverResellerTypeId  : it.receiverResellerTypeId,
                senderBalanceBefore     : it.senderBalanceBefore,
                senderBalanceAfter      : it.senderBalanceAfter,
                receiverBalanceBefore   : it.receiverBalanceBefore,
                receiverBalanceAfter    : it.receiverBalanceAfter,
                hour                    : it.hour,
                lessThanOne             : it.lessThanOne,
                greaterThanTen          : it.greaterThanTen,
				accountLinkTypeId		: it.accountLinkTypeId
            )
    }

    private def statistics =
            {   key, list ->
                [key: key, total: list.size()]
            }

    private def updateAggregation(List aggregation)
    {
        log.info("Aggregated into StdTransactionDetailsStatisticAggregator.batch ${aggregation.size()} rows.")
        if(aggregation)
        {

            // then insert new data
            def sql = "INSERT INTO ${TABLE} (transactionDate,transactionReference,transactionType,senderMSISDN,senderResellerID,receiverMSISDN,transactionAmount,channel,transactionStatus,resultDescription,mmTransactionID,receiverResellerID,senderResellerTypeId,receiverResellerTypeId ,senderBalanceBefore ,senderBalanceAfter ,receiverBalanceBefore ,receiverBalanceAfter,transactionHour, lessThanOne,greaterThanTen,accountLinkTypeId) VALUES (?, ?, ?,?,?,?,?,?,?,?,?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,?)"
            def batchUpdate = jdbcTemplate.batchUpdate(sql, [
                    setValues:
                            {   ps, i ->
                                def row = aggregation[i]

                                ps.setTimestamp(1, toSqlTimestamp(row.key.date))
                                ps.setString (2, row.key.ersReference)
                                ps.setString(3, row.key.transactionType)
                                ps.setString(4, row.key.senderMSISDN)
                                ps.setString(5, row.key.senderResellerID)
                                ps.setString(6, row.key.receiverMSISDN)
                                ps.setString(7,  row.key.transactionAmount)
                                ps.setString(8, row.key.channel)
                                ps.setString(9, row.key.transactionStatus)
                                ps.setString(10, row.key.resultDescription)
                                ps.setString(11, row.key.momTransactionID)
                                ps.setString(12,row.key.receiverResellerID)
                                ps.setString(13,row.key.senderResellerTypeId)
                                ps.setString(14,row.key.receiverResellerTypeId)
                                ps.setBigDecimal(15,row.key.senderBalanceBefore)
                                ps.setBigDecimal(16,row.key.senderBalanceAfter)
                                ps.setBigDecimal(17,row.key.receiverBalanceBefore)
                                ps.setBigDecimal(18,row.key.receiverBalanceAfter)
                                ps.setString(19, row.key.hour)
                                ps.setBigDecimal(20,row.key.lessThanOne)
                                ps.setBigDecimal(21,row.key.greaterThanTen)
								ps.setString(22,row.key.accountLinkTypeId)
                            },
                    getBatchSize:
                            { aggregation.size() }
            ] as BatchPreparedStatementSetter)
        }
    }
}

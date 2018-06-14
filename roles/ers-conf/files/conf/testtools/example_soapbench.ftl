<?xml version="1.0" encoding="UTF-8"?>
<bench>
<#list 1..2 as idx>
    <task>
    	<sendsoap id="principalInfo" endpointId="txp" expectedResponse=".*.resultCode.0./resultCode..*resellerMSISDN.([0-9]+)./resellerMSISDN.*">
    		<message>
	    		<![CDATA[
					<soapenv:Envelope xmlns:soapenv="http://schemas.xmlsoap.org/soap/envelope/" xmlns:proc="http://processing.transaction.clients.platform.interfaces.ers.seamless.com/">
					   <soapenv:Header/>
					   <soapenv:Body>
					      <proc:requestPrincipalInformation>
					         <context>
					            <clientId>WS</clientId>
					            <principalId>
					               <id>SE-08-001/9900</id>
					               <type>RESELLERUSER</type>
					            </principalId>
					            <securityToken>
					               <data>2009</data>
					               <type>PASSWORD</type>
					            </securityToken>
					         </context>
					      </proc:requestPrincipalInformation>
					   </soapenv:Body>
					</soapenv:Envelope>
				]]>
    		</message>
    	</sendsoap>
    </task>
</#list>
</bench>
-- -----------------------------------------------------
-- Template for creating standard search tables
--
-- $Id$
-- -----------------------------------------------------
CREATE TABLE ${searchtable.tableName} (
  `searchField` varchar(40) NOT NULL DEFAULT '',
  `searchFieldTypeFlags` int unsigned DEFAULT NULL,
  `transactionKey` bigint DEFAULT NULL,
  `startTime` DATETIME NOT NULL DEFAULT '0000-00-00 00:00:00',
  `ersReference` varchar(25) NOT NULL DEFAULT '',
  `transactionType` varchar(30) DEFAULT NULL,
  `profileId` varchar(50) DEFAULT NULL,
  `searchTagsFlags` int unsigned DEFAULT NULL,
  `channel` varchar(20) DEFAULT NULL,
  `resultCode` smallint unsigned DEFAULT NULL,
  KEY `searchField_startTime` (`searchField`,`startTime`),
  KEY `transactionKey` (`transactionKey`),
  PRIMARY KEY (`startTime`, `searchField`, `ersReference`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 
<#if partitions ??>
<#assign pnum = 1>
    PARTITION BY RANGE( TO_DAYS(startTime) )
    (
 <#list partitions as partition>
    PARTITION p${pnum} VALUES LESS THAN (TO_DAYS('${partition?string('yyyy-MM-dd')}')), 
  <#assign pnum = pnum + 1>
 </#list>
     PARTITION pMax VALUES LESS THAN MAXVALUE
    )
  <#else>
</#if>
;

-- // [FUL-1664] Create table in db to track package statuses
-- Migration SQL that makes the change goes here.
CREATE TABLE `TrackingStatus` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `trackingNumber` varchar(255) NOT NULL DEFAULT '',
  `trackerId` varchar(255) DEFAULT '',
  `weight` decimal(10,2) DEFAULT NULL,
  `estimatedDeliveryDate` timestamp NULL DEFAULT NULL,
  `carrier` varchar(10) DEFAULT '',
  `status` varchar(30) NOT NULL DEFAULT '',
  `updated` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `shippingMethod` varchar(60) DEFAULT '',
  `packageId` varchar(255) DEFAULT '',
  PRIMARY KEY (`id`),
  UNIQUE KEY `packageId` (`packageId`),
  KEY `trackerId` (`trackerId`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

CREATE TABLE `TrackingHistory` (
  `id` bigint(20) NOT NULL AUTO_INCREMENT,
  `trackingStatus_id` bigint(20) NOT NULL,
  `created` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `message` varchar(655) DEFAULT NULL,
  `currentCity` varchar(60) DEFAULT NULL,
  `currentState` varchar(60) DEFAULT NULL,
  `currentCountry` varchar(60) DEFAULT NULL,
  `currentZip` varchar(60) DEFAULT NULL,
  `status` varchar(60) DEFAULT NULL,
  PRIMARY KEY (`id`),
  KEY `trackingStatus_id` (`trackingStatus_id`),
  CONSTRAINT `trackinghistory_ibfk_1` FOREIGN KEY (`trackingStatus_id`) REFERENCES `TrackingStatus` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

-- //@UNDO
-- SQL to undo the change goes here.



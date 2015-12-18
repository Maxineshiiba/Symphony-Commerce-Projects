# ************************************************************
# Sequel Pro SQL dump
# Version 4096
#
# http://www.sequelpro.com/
# http://code.google.com/p/sequel-pro/
#
# Host: us-cdbr-iron-east-02.cleardb.net (MySQL 5.5.42-log)
# Database: heroku_28cf5e805bee583
# Generation Time: 2015-04-21 19:19:55 +0000
# ************************************************************


/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
/*!40101 SET @OLD_CHARACTER_SET_RESULTS=@@CHARACTER_SET_RESULTS */;
/*!40101 SET @OLD_COLLATION_CONNECTION=@@COLLATION_CONNECTION */;
/*!40101 SET NAMES utf8 */;
/*!40014 SET @OLD_FOREIGN_KEY_CHECKS=@@FOREIGN_KEY_CHECKS, FOREIGN_KEY_CHECKS=0 */;
/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE, SQL_MODE='NO_AUTO_VALUE_ON_ZERO' */;
/*!40111 SET @OLD_SQL_NOTES=@@SQL_NOTES, SQL_NOTES=0 */;


# Dump of table carrier
# ------------------------------------------------------------

DROP TABLE IF EXISTS `carrier`;

CREATE TABLE `carrier` (
  `service_provider` varchar(128) NOT NULL,
  `symphony_code` varchar(128) NOT NULL,
  `provider_code` varchar(128) NOT NULL,
  `direction` varchar(32) NOT NULL DEFAULT 'BOTH',
  PRIMARY KEY (`service_provider`,`symphony_code`,`provider_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

LOCK TABLES `carrier` WRITE;
/*!40000 ALTER TABLE `carrier` DISABLE KEYS */;

/*!40000 ALTER TABLE `carrier` ENABLE KEYS */;
UNLOCK TABLES;


# Dump of table shipping_method
# ------------------------------------------------------------

DROP TABLE IF EXISTS `shipping_method`;

CREATE TABLE `shipping_method` (
  `service_provider` varchar(128) NOT NULL,
  `symphony_code` varchar(128) NOT NULL,
  `provider_code` varchar(128) NOT NULL,
  `direction` varchar(32) NOT NULL DEFAULT 'BOTH',
  PRIMARY KEY (`service_provider`,`symphony_code`,`provider_code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

LOCK TABLES `shipping_method` WRITE;
/*!40000 ALTER TABLE `shipping_method` DISABLE KEYS */;

INSERT INTO `shipping_method` (`service_provider`, `symphony_code`, `provider_code`, `direction`)
VALUES
  ('DM','FEDEX.2DAY.SHIPPING','3049','BOTH'),
  ('DM','FEDEX.EXPRESS.SAVER.ECONOMY','3046','BOTH'),
  ('DM','FEDEX.GROUND','3045','BOTH'),
  ('DM','FEDEX.HOME.DELIVERY','3050','BOTH'),
  ('DM','FEDEX.OVERNIGHT.STANDARD','3048','BOTH'),
  ('DM','FEDEX.PRIORITY.OVERNIGHT','3047','BOTH'),
  ('DM','FEDEX.SMARTPOST.STANDARD','3171','BOTH'),
  ('DM','PICK.UP','415','BOTH'),
  ('DM','UPS.2ND.DAY.AIR','494','BOTH'),
  ('DM','UPS.3.DAY.SELECT','498','BOTH'),
  ('DM','UPS.GROUND','490','BOTH'),
  ('DM','UPS.NEXT.DAY.AIR','500','BOTH'),
  ('DM','UPS.NEXT.DAY.AIR.SAVER','491','BOTH'),
  ('DM','UPS.STANDARD.CANADA','3103','BOTH'),
  ('DM','UPS.WORLDWIDE.EXPEDITED','3102','BOTH'),
  ('DM','UPS.WORLDWIDE.EXPRESS','3101','BOTH'),
  ('DM','UPS.WORLDWIDE.EXPRESS.PLUS','3104','BOTH'),
  ('DM','UPS.WORLDWIDE.EXPRESS.SAVER','3105','BOTH'),
  ('DM','USPS.EXPRESS.MAIL','505','BOTH'),
  ('DM','USPS.EXPRESS.MAIL.INTERNATIONAL','3115','BOTH'),
  ('DM','USPS.FIRST.CLASS.MAIL','413','BOTH'),
  ('DM','USPS.FIRSTCLASS.MAIL.INTERNATIONAL','3122','BOTH'),
  ('DM','USPS.PARCEL.SELECT.NONPRESORT','3135','BOTH'),
  ('DM','USPS.PRIORITY','486','BOTH'),
  ('DM','USPS.PRIORITY.MAIL.INTERNATIONAL','3114','BOTH');

/*!40000 ALTER TABLE `shipping_method` ENABLE KEYS */;
UNLOCK TABLES;


# Dump of table warehouse
# ------------------------------------------------------------

DROP TABLE IF EXISTS `warehouse`;

CREATE TABLE `warehouse` (
  `service_provider` varchar(128) NOT NULL,
  `symphony_code` varchar(32) NOT NULL,
  `provider_code` varchar(128) NOT NULL,
  `provider_code_alt` varchar(128),
  PRIMARY KEY (`service_provider`,`symphony_code`,`provider_code`,`provider_code_alt`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

LOCK TABLES `warehouse` WRITE;
/*!40000 ALTER TABLE `warehouse` DISABLE KEYS */;

INSERT INTO `warehouse` (`service_provider`, `symphony_code`, `provider_code`,`provider_code_alt`)
VALUES
  ('DM','DM_CA_FAT1','6','CA'),
  ('DM','DM_MO_STL1','1','MO'),
  ('DM','DM_PA_MDT1','5','PA'),
  ('DM','DM_TX_DFW1','7','TX');

/*!40000 ALTER TABLE `warehouse` ENABLE KEYS */;
UNLOCK TABLES;

# Dump of table whiteListed_order
# ------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `whiteListed_order` (
  `order_id` varchar(128) NOT NULL,
  PRIMARY KEY (`order_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8;

LOCK TABLES `whiteListed_order` WRITE;
/*!40000 ALTER TABLE `whiteListed_order` DISABLE KEYS */;
   
/*!40000 ALTER TABLE `whiteListed_order` ENABLE KEYS */;
UNLOCK TABLES;

/*!40111 SET SQL_NOTES=@OLD_SQL_NOTES */;
/*!40101 SET SQL_MODE=@OLD_SQL_MODE */;
/*!40014 SET FOREIGN_KEY_CHECKS=@OLD_FOREIGN_KEY_CHECKS */;
/*!40101 SET CHARACTER_SET_CLIENT=@OLD_CHARACTER_SET_CLIENT */;
/*!40101 SET CHARACTER_SET_RESULTS=@OLD_CHARACTER_SET_RESULTS */;
/*!40101 SET COLLATION_CONNECTION=@OLD_COLLATION_CONNECTION */;

#Split Month and Date from rest of message
#Example row:
#November 5 10:57 am Delivered, Front Door/Porch in SAN FRANCISCO, CA

SELECT
   SUBSTRING_INDEX(SUBSTRING_INDEX(`message`, ' ', 1), ' ', -1) AS DeliveryMonth,
   If(  length(`message`) - length(replace(`message`, ' ', ''))>1,  
       SUBSTRING_INDEX(SUBSTRING_INDEX(`message`, ' ', 2), ' ', -1) ,NULL) AS DeliveryDate,
       currentZip, currentStatus,
       TRIM( SUBSTR(`message`, LOCATE(' ', `message`,9)) ) AS Message
FROM TrackingHistory




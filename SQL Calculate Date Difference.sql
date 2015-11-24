##SQL code to calculate date difference between two rows

select A.id, A.trackingStatus_id, A.updatedAt, A.currentZip, (Date(B.updatedAt) - Date(A.updatedAt)) as timediff
from TrackingHistory A inner join TrackingHistory B on B.id =(A.id + 1) order by A.id ASC

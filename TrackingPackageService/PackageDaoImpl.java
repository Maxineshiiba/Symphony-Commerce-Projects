package sneakpeeq.core.daos.FAS;

import org.hibernate.Criteria;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.joda.time.LocalDate;
import org.springframework.stereotype.Repository;
import sneakpeeq.core.daos.impl.CustomHibernateDaoSupport;
import sneakpeeq.core.models.FAS.Package;

import java.util.List;

@Repository
@SuppressWarnings("unchecked")
public class PackageDaoImpl extends CustomHibernateDaoSupport<Package> implements PackageDao {

	public List<Package> findByShipmentId(Long shipmentId) {

		Criteria criteria = getSession().createCriteria(Package.class);
		criteria.setResultTransformer(Criteria.DISTINCT_ROOT_ENTITY);
		criteria.add(Restrictions.eq("this.shipment.id", shipmentId));
		criteria.addOrder(Order.desc("id"));
		return criteria.list();
	}

	public List<Package> getPackageByTrackingCode(String trackingCode) {
		Criteria criteria = getSession().createCriteria(Package.class);
		criteria.add(Restrictions.eq("trackingCode", trackingCode));
		return criteria.list();
	}

	@Override
	public List<Long> findIdsByCreatedDate(LocalDate createdDate) {
		return createQuery("SELECT id FROM Package p WHERE p.created >= " +
				":startDate AND p.created < :endDate")
				.setTimestamp("startDate", createdDate.toDate())
				.setTimestamp("endDate", createdDate.plusDays(1).toDate())
				.list();
	}
}

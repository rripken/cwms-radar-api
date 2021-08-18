package cwms.radar.data.dao;

import cwms.radar.data.dto.basinconnectivity.Basin;
import cwms.radar.data.dto.basinconnectivity.Stream;
import org.jooq.DSLContext;
import usace.cwms.db.jooq.dao.CwmsDbBasinJooq;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

public class BasinDao extends JooqDao<Basin>
{
    public BasinDao(DSLContext dsl)
    {
        super(dsl);
    }

    public Basin getBasin(String basinId, String officeId) throws SQLException
    {
        CwmsDbBasinJooq basinJooq = new CwmsDbBasinJooq();
        String[] pParentBasinId = new String[1];
        Double[] pSortOrder = new Double[1];
        String[] pPrimaryStreamId = new String[1];
        Double[] pTotalDrainageArea = new Double[1];
        Double[] pContributingDrainageArea = new Double[1];
        String areaUnit = "km2";
        Connection c = dsl.configuration().connectionProvider().acquire();
        ResultSet rs = basinJooq.catBasins(c, (String) null, (String) null, (String) null, areaUnit, (String) null);

        basinJooq.retrieveBasin(c, pParentBasinId, pSortOrder, pPrimaryStreamId, pTotalDrainageArea, pContributingDrainageArea, basinId, areaUnit, officeId);
        Stream primaryStream = null;
        if(pPrimaryStreamId[0] != null)
        {
            StreamDao streamDao = new StreamDao(dsl);
            primaryStream = streamDao.getStream(pPrimaryStreamId[0], officeId);
        }
        return new Basin(basinId, primaryStream);
    }

    @Override
    public Optional getByUniqueName(String uniqueName, Optional limitToOffice)
    {
        return Optional.empty();
    }

    @Override
    public List getAll(Optional limitToOffice)
    {
        return null;
    }

}

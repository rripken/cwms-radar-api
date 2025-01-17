package cwms.radar;

import java.io.IOException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.annotation.Resource;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.sql.DataSource;

import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.servlets.MetricsServlet;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import cwms.radar.api.BasinController;
import cwms.radar.api.BlobController;
import cwms.radar.api.CatalogController;
import cwms.radar.api.ClobController;
import cwms.radar.api.LevelsController;
import cwms.radar.api.LocationCategoryController;
import cwms.radar.api.LocationController;
import cwms.radar.api.LocationGroupController;
import cwms.radar.api.NotFoundException;
import cwms.radar.api.OfficeController;
import cwms.radar.api.ParametersController;
import cwms.radar.api.PoolController;
import cwms.radar.api.RatingController;
import cwms.radar.api.TimeSeriesCategoryController;
import cwms.radar.api.TimeSeriesController;
import cwms.radar.api.TimeSeriesGroupController;
import cwms.radar.api.TimeZoneController;
import cwms.radar.api.UnitsController;
import cwms.radar.api.enums.UnitSystem;
import cwms.radar.api.errors.ExclusiveFieldsException;
import cwms.radar.api.errors.FieldException;
import cwms.radar.api.errors.JsonFieldsException;
import cwms.radar.api.errors.RadarError;
import cwms.radar.api.errors.RequiredFieldException;
import cwms.radar.formatters.Formats;
import cwms.radar.formatters.FormattingException;
import cwms.radar.security.Role;
import cwms.radar.security.CwmsAccessManager;
import io.javalin.Javalin;
import io.javalin.apibuilder.CrudFunction;
import io.javalin.apibuilder.CrudHandler;
import io.javalin.apibuilder.CrudHandlerKt;
import io.javalin.core.security.AccessManager;
import io.javalin.core.security.RouteRole;
import io.javalin.core.validation.JavalinValidation;
import io.javalin.http.BadRequestResponse;
import io.javalin.http.Handler;
import io.javalin.http.JavalinServlet;
import io.javalin.plugin.openapi.OpenApiOptions;
import io.javalin.plugin.openapi.OpenApiPlugin;
import io.swagger.v3.oas.models.info.Info;
import org.apache.http.entity.ContentType;
import org.jetbrains.annotations.NotNull;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import static io.javalin.apibuilder.ApiBuilder.get;
import static io.javalin.apibuilder.ApiBuilder.crud;
import static io.javalin.apibuilder.ApiBuilder.prefixPath;
import static io.javalin.apibuilder.ApiBuilder.staticInstance;


/**
 * Setup all the information required so we can serve the request.
 *
 */
@WebServlet(urlPatterns = { "/catalog/*",
                            "/swagger-docs",
                            "/timeseries/*",
                            "/offices/*",
                            "/location/*",
                            "/locations/*",
                            "/parameters/*",
                            "/timezones/*",
                            "/units/*",
                            "/ratings/*",
                            "/levels/*",
                            "/basins/*",
                            "/blobs/*",
                            "/clobs/*",
                            "/pools/*"
})
public class ApiServlet extends HttpServlet {
    public static final Logger logger = Logger.getLogger(ApiServlet.class.getName());

    // based on https://bitbucket.hecdev.net/projects/CWMS/repos/cwms_aaa/browse/IntegrationTests/src/test/resources/sql/load_testusers.sql
    public static final String CWMS_USERS_ROLE = "CWMS Users";

    private MetricRegistry metrics;
    private Meter total_requests;
    /**
     *
     */
    private static final long serialVersionUID = 1L;

    static JavalinServlet javalin = null;

    @Resource(name = "jdbc/CWMS3")
    DataSource cwms;

    @Override
    public void init() throws ServletException{

        String context = this.getServletContext().getContextPath();

        PolicyFactory sanitizer = new HtmlPolicyBuilder().disallowElements("<script>").toFactory();
        ObjectMapper om = new ObjectMapper();
        JavalinValidation.register(UnitSystem.class, UnitSystem::systemFor);
        om.setPropertyNamingStrategy(PropertyNamingStrategies.KEBAB_CASE);
        om.registerModule(new JavaTimeModule());            // Needed in Java 8 to properly format java.time classes

        AccessManager accessManager = buildAccessManager();


        javalin = Javalin.createStandalone(config -> {
            config.defaultContentType = "application/json";
            config.contextPath = context;
            config.registerPlugin(new OpenApiPlugin(getOpenApiOptions()));
            config.enableDevLogging();
            config.requestLogger( (ctx,ms) -> logger.finest(ctx.toString()));
            config.accessManager(accessManager);
        })
                .attribute("PolicyFactory",sanitizer)
                .attribute("ObjectMapper",om)
                .before( ctx -> {

                    ctx.attribute("sanitizer",sanitizer);
                    ctx.header("X-Content-Type-Options","nosniff");
                    ctx.header("X-Frame-Options","SAMEORIGIN");
                    ctx.header("X-XSS-Protection", "1; mode=block");
                })
                .exception(FormattingException.class, (fe, ctx ) -> {
                    final RadarError re = new RadarError("Formatting error");

                    if( fe.getCause() instanceof IOException ){
                        ctx.status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    } else {
                        ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED);
                    }
                    logger.log(Level.SEVERE,fe, () -> re + "for request: " + ctx.fullUrl());
                    ctx.json(re);
                })
                .exception(UnsupportedOperationException.class, (e, ctx) -> {
                    final RadarError re = RadarError.notImplemented();
                    logger.log(Level.WARNING, e, () -> re + "for request: " + ctx.fullUrl() );
                    ctx.status(HttpServletResponse.SC_NOT_IMPLEMENTED).json(re);
                })
                .exception(BadRequestResponse.class, (e, ctx) -> {
                    RadarError re = new RadarError("Bad Request", e.getDetails());
                    logger.log(Level.INFO, re.toString(), e );
                    ctx.status(e.getStatus()).json(re);
                })
                .exception(IllegalArgumentException.class, (e, ctx ) -> {
                    RadarError re = new RadarError("Bad Request");
                    logger.log(Level.INFO, re.toString(), e );
                    ctx.status(HttpServletResponse.SC_BAD_REQUEST).json(re);
                })
                .exception(NotFoundException.class, (e, ctx ) -> {
                    RadarError re = new RadarError("Not Found.");
                    logger.log(Level.INFO, re.toString(), e );
                    ctx.status(HttpServletResponse.SC_NOT_FOUND).json(re);
                })
                .exception(FieldException.class, (e,ctx) -> {
                    RadarError re = new RadarError(e.getMessage(),e.getDetails(),true);
                    ctx.status(HttpServletResponse.SC_BAD_REQUEST).json(re);
                })
                .exception(JsonFieldsException.class, (e,ctx) -> {
                    RadarError re = new RadarError(e.getMessage(),e.getDetails(),true);
                    ctx.status(HttpServletResponse.SC_BAD_REQUEST).json(re);
                })
                .exception(Exception.class, (e,ctx) -> {
                    RadarError errResponse = new RadarError("System Error");
                    logger.log(Level.WARNING,String.format("error on request[%s]: %s", errResponse.getIncidentIdentifier(), ctx.req.getRequestURI()),e);
                    ctx.status(500);
                    ctx.contentType(ContentType.APPLICATION_JSON.toString());
                    ctx.json(errResponse);
                })

                .routes(this::configureRoutes)
                .javalinServlet();
    }

    private AccessManager buildAccessManager()
    {
        return new CwmsAccessManager();
    }




    protected void configureRoutes()
    {

        RouteRole[] requiredRoles = {new Role(CWMS_USERS_ROLE)};

        get("/", ctx -> ctx.result("Welcome to the CWMS REST API").contentType(Formats.PLAIN));
        radarCrud("/location/category/{category-id}", new LocationCategoryController(metrics), requiredRoles);
        radarCrud("/location/group/{group-id}", new LocationGroupController(metrics), requiredRoles);
        radarCrud("/locations/{location_code}", new LocationController(metrics), requiredRoles);
        radarCrud("/offices/{office}", new OfficeController(metrics), requiredRoles);
        radarCrud("/units/{unit_name}", new UnitsController(metrics), requiredRoles);
        radarCrud("/parameters/{param_name}", new ParametersController(metrics), requiredRoles);
        radarCrud("/timezones/{zone}", new TimeZoneController(metrics), requiredRoles);
        radarCrud("/levels/{location}", new LevelsController(metrics), requiredRoles);
        TimeSeriesController tsController = new TimeSeriesController(metrics);
        get("/timeseries/recent/{group-id}", tsController::getRecent);
        radarCrud("/timeseries/category/{category-id}", new TimeSeriesCategoryController(metrics), requiredRoles);
        radarCrud("/timeseries/group/{group-id}", new TimeSeriesGroupController(metrics), requiredRoles);
        radarCrud("/timeseries/{timeseries}", tsController, requiredRoles);
        radarCrud("/ratings/{rating}", new RatingController(metrics), requiredRoles);
        radarCrud("/catalog/{dataSet}", new CatalogController(metrics), requiredRoles);
        radarCrud("/basins/{basin-id}", new BasinController(metrics), requiredRoles);
        radarCrud("/blobs/{blob-id}", new BlobController(metrics), requiredRoles);
        radarCrud("/clobs/{clob-id}", new ClobController(metrics), requiredRoles);
        radarCrud("/pools/{pool-id}", new PoolController(metrics), requiredRoles);
    }


    /**
     * This method is very similar to the ApiBuilder.crud method but the specified roles
     * are only required for the post, patch and delete methods.    getOne and getAll are always allowed.
     * @param path where to register the routes.
     * @param crudHandler the handler requests should be forwarded to.
     * @param roles the accessmanager will require these roles are present to access post, patch and delete methods
     */
    public static void radarCrud(@NotNull String path, @NotNull CrudHandler crudHandler, @NotNull RouteRole... roles) {
        String fullPath = prefixPath(path);
        String[] subPaths = Arrays.stream(fullPath.split("/")).filter(it -> !it.isEmpty()).toArray(String[]::new);
        if (subPaths.length < 2) {
            throw new IllegalArgumentException("CrudHandler requires a path like '/resource/{resource-id}'");
        }
        String resourceId = subPaths[subPaths.length - 1];
        if (!(resourceId.startsWith("{") && resourceId.endsWith("}"))) {
            throw new IllegalArgumentException("CrudHandler requires a path-parameter at the end of the provided path, e.g. '/users/{user-id}'");
        }
        String resourceBase = subPaths[subPaths.length - 2];
        if (resourceBase.startsWith("{") || resourceBase.startsWith("<") || resourceBase.endsWith("}") || resourceBase.endsWith(">")) {
            throw new IllegalArgumentException("CrudHandler requires a resource base at the beginning of the provided path, e.g. '/users/{user-id}'");
        }

        Map<CrudFunction, Handler> crudFunctions = CrudHandlerKt.getCrudFunctions(crudHandler, resourceId);//getHanders(crudHandler, resourceId);

        // getOne and getAll are assumed not to need authorization
        staticInstance().get(fullPath, crudFunctions.get(CrudFunction.GET_ONE));
        staticInstance().get(fullPath.replace(resourceId, ""), crudFunctions.get(CrudFunction.GET_ALL));

        // create, update and delete need authorization.
        staticInstance().post(fullPath.replace(resourceId, ""), crudFunctions.get(CrudFunction.CREATE), roles);
        staticInstance().patch(fullPath, crudFunctions.get(CrudFunction.UPDATE), roles);
        staticInstance().delete(fullPath, crudFunctions.get(CrudFunction.DELETE), roles);
    }

    private OpenApiOptions getOpenApiOptions() {
        Info applicationInfo = new Info().title("CWMS Radar").version("2.0").description("CWMS REST API for Data Retrieval");
        return new OpenApiOptions(applicationInfo)
                    .path("/swagger-docs")
                    .defaultDocumentation((doc) -> {
                        doc.json("500", RadarError.class);
                        doc.json("400", RadarError.class);
                        doc.json("401", RadarError.class);
                        doc.json("403", RadarError.class);
                        doc.json("404", RadarError.class);
                    })
                    .activateAnnotationScanningFor("cwms.radar.api");
    }

    @Override
    protected void service(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        total_requests.mark();
        try (Connection db = cwms.getConnection()) {
            String office = officeFromContext(req.getContextPath());
            req.setAttribute("office_id", office);
            req.setAttribute("database", db);
            javalin.service(req, resp);
        } catch (SQLException ex) {
            RadarError re = new RadarError("Major Database Issue");
            logger.log(Level.SEVERE, ex, () -> re + " for url " + req.getRequestURI());
            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            resp.setContentType(ContentType.APPLICATION_JSON.toString());
            try (PrintWriter out = resp.getWriter()) {
                ObjectMapper om = new ObjectMapper();
                out.println(om.writeValueAsString(re));
            }

        }

    }

    @Override
    public void init(ServletConfig config) throws ServletException {
        metrics = (MetricRegistry)config.getServletContext().getAttribute(MetricsServlet.METRICS_REGISTRY);
        total_requests = metrics.meter("radar.total_requests");
        super.init(config);
    }

    public static String officeFromContext(String contextPath){
        String office = contextPath.split("\\-")[0].replaceFirst("/","");
        if( office.isEmpty() || office.equalsIgnoreCase("cwms")){
            office = "HQ";
        }
        return office.toUpperCase();
    }

}

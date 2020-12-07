/*
 * The MIT License
 *
 * Copyright 2020 mike.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package cwms.radar.api;

import io.javalin.apibuilder.CrudHandler;
import io.javalin.http.Context;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpServletResponse;

import cwms.radar.data.CwmsDataManager;


/**
 *
 * @author mike
 */
public class LocationController implements CrudHandler {
    
    

    @Override
    public void getAll(Context ctx) {
        try (
                CwmsDataManager cdm = new CwmsDataManager(ctx);
            ) {
                String format = ctx.queryParam("format","json");           
                String names = ctx.queryParam("names");
                String units = ctx.queryParam("units");
                String datum = ctx.queryParam("datum");
                String office = ctx.queryParam("office");


                switch(format){
                    case "json": {ctx.contentType("application/json"); break;}
                    case "tab": {ctx.contentType("text/tab-sperated-values");break;}
                    case "csv": {ctx.contentType("text/csv"); break;}
                    case "xml": {ctx.contentType("application/xml");break;}
                    case "wml2": {ctx.contentType("application/xml");break;}
                }

                String results = cdm.getLocations(names,format,units,datum,office);                
                ctx.status(HttpServletResponse.SC_OK);
                ctx.result(results);                
        } catch (SQLException ex) {
            Logger.getLogger(LocationController.class.getName()).log(Level.SEVERE, null, ex);
            ctx.status(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            ctx.result("Failed to process request");
        }
    }

    @Override
    public void getOne(Context ctx, String location_code) {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    @Override
    public void create(Context ctx) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void update(Context ctx, String location_code) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    @Override
    public void delete(Context ctx, String location_code) {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

}

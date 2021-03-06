// <copyright file="MinusComparator.java" company="Objectivity Bespoke Software Specialists">
// Copyright (c) Objectivity Bespoke Software Specialists. All rights reserved.
// </copyright>
// <license>
//     The MIT License (MIT)
//     Permission is hereby granted, free of charge, to any person obtaining a copy
//     of this software and associated documentation files (the "Software"), to deal
//     in the Software without restriction, including without limitation the rights
//     to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
//     copies of the Software, and to permit persons to whom the Software is
//     furnished to do so, subject to the following conditions:
//     The above copyright notice and this permission notice shall be included in all
//     copies or substantial portions of the Software.
//     THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
//     IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
//     FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
//     AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
//     LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
//     OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
//     SOFTWARE.
// </license>

package uk.co.objectivity.test.db.comparators;

import java.io.File;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.testng.TestException;

import uk.co.objectivity.test.db.beans.TestParams;
import uk.co.objectivity.test.db.beans.TestResults;
import uk.co.objectivity.test.db.beans.xml.CmpSqlResultsConfig;
import uk.co.objectivity.test.db.beans.xml.CmpSqlResultsTest;
import uk.co.objectivity.test.db.beans.xml.Compare;
import uk.co.objectivity.test.db.beans.xml.Datasource;
import uk.co.objectivity.test.db.utils.DataSource;
import uk.co.objectivity.test.db.utils.SavedTimes;

import static uk.co.objectivity.test.db.TestDataProvider.savedTimesList;

public class MinusComparator extends Comparator {

    private final static Logger log = Logger.getLogger(MinusComparator.class);

    @Override
    public TestResults compare(TestParams testParams) throws Exception {
        CmpSqlResultsConfig cmpSqlResultsConfig = testParams.getCmpSqlResultsConfig();
        CmpSqlResultsTest cmpSqlResultsTest = testParams.getCmpSqlResultsTest();

        // TODO xml config validation -> in MINUS mode sql queries shpould NOT have own datasources - only
        // default on "compare" level (in XML). Schema would be nice!
        String dataSrcName = cmpSqlResultsTest.getCompare().getDefaultDatasourceName();
        Datasource datasource = cmpSqlResultsConfig.getDatasourceByName(dataSrcName);
        if (datasource == null) {
            throw new TestException("Datasource '" + dataSrcName + "' not found! Please check configuration");
        }
        // (get(0)/get(1) - IndexOutOfBoundsException) checked in TestDataProvider, but still - schema would be nice!
        String sql1 = cmpSqlResultsTest.getCompare().getSqls().get(0).getSql();
        String sql2 = cmpSqlResultsTest.getCompare().getSqls().get(1).getSql();
        String query = getMinusQuery(datasource, sql1, sql2, testParams);

        Connection connection = null;
        try {
            connection = DataSource.getConnection(datasource.getName());
            return getTestResults(connection, query, testParams, dataSrcName);
        } catch (Exception e) {
            throw new Exception(e+"\nMinus Query : " + query);
        } finally {
            DataSource.closeConnection(connection);
        }
    }

    public TestResults getTestResults(Connection conn, String query, TestParams testParams,String dataSrcName) throws Exception {
        Compare compare = testParams.getCmpSqlResultsTest().getCompare();
        CmpSqlResultsTest cmpSqlResultsTest = testParams.getCmpSqlResultsTest();
        PreparedStatement stmt = null;
        PrintWriter minusPWriter = null;
        int rowCount = 0;
        SavedTimes savedTimes = new SavedTimes(testParams.getTestName());
        File diffFileName = getNewFileBasedOnTestConfigFile(testParams.getTestConfigFile(), "_minus.csv");
        try {
            boolean countOnly = compare.getDiffTableSize() <= 0;

            String executedQuery = countOnly ? getCountQuery(query) : query;

            stmt = conn.prepareStatement(executedQuery, ResultSet.TYPE_SCROLL_INSENSITIVE,
                    ResultSet.CONCUR_READ_ONLY);
            savedTimes.startMeasure("Minus " + compare.getDefaultDatasourceName());
            ResultSet rs = stmt.executeQuery();
            savedTimes.stopMeasure();
            savedTimesList.add(savedTimes);

            String minusQueryIndicator=", Minus Query Indicator: " + compare.isMinusQueryIndicatorOn();
            if(compare.isMinusQueryIndicatorOn()){
                minusQueryIndicator=minusQueryIndicator+
                        ", Minus First Query Indicator Occurence: " + cmpSqlResultsTest.getCompare().getSqls().get(0).getMinusQueryIndicatorOccurence()+
                        ", Minus First Query Indicator Text: " + cmpSqlResultsTest.getCompare().getSqls().get(0).getMinusQueryIndicatorText()+
                        "\n,Minus Second Query Indicator Occurence: " + cmpSqlResultsTest.getCompare().getSqls().get(1).getMinusQueryIndicatorOccurence()+
                        ", Minus Second Query Indicator Text: " + cmpSqlResultsTest.getCompare().getSqls().get(1).getMinusQueryIndicatorText();
            }
            executedQuery = "Datasource: " + dataSrcName + "\r\n" + executedQuery +
                    "\r\nDifftable size: " + compare.getDiffTableSize() +
                    minusQueryIndicator+
                    ", File output: " + compare.isFileOutputOn() + "\r\n"+
                    "Time execution of query:\n"+
                    savedTimes.getFormattedDuration();
            if (countOnly) {
                rs.next();
                return new TestResults(executedQuery, rs.getInt(1));
            }

            if (rs.last()) {
                rowCount = rs.getRow();
                rs.beforeFirst(); // not rs.first() because the rs.next() below will move on, missing the first element
            }

            TestResults testResults = new TestResults(executedQuery, rowCount);
            if (rowCount == 0) {
                return testResults;
            }

            if (compare.isFileOutputOn()) {
                minusPWriter = new PrintWriter(diffFileName);
            }

            // building columns
            List<String> columns = new ArrayList<>();
            int colCount = rs.getMetaData().getColumnCount();
            for (int column = 1; column <= colCount; column++) {
                columns.add(rs.getMetaData().getColumnName(column));
            }
            testResults.setColumns(columns);
            // building rows
            List<List<String>> rows = new ArrayList<>();
            int diffTabSize = compare.getDiffTableSize();
            int chunk = compare.getChunk();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int colIndex = 1; colIndex <= colCount; colIndex++) {
                    Object rowObj = rs.getObject(colIndex);
                    // we want NULL to be displayed (to see differences between empty strings)
                    row.add(rowObj == null ? "<NULL>" : rowObj.toString());
                }
                writeRowAsCSV(minusPWriter, row);
                if (diffTabSize-- > 0) {
                    rows.add(row);
                } else if (!compare.isFileOutputOn()) {
                    // we break even if chunk=0 (unlimited). If file log is turned off (why should we go further - we
                    // already know nmb of results)
                    break;
                }
                // chunk has higher priority than diffTabSize
                if (chunk > 0 && rs.getRow() >= chunk) {
                    break;
                }
            }
            testResults.setRows(rows);
            return testResults;
        } finally {
            if(rowCount==0){
                savedTimes.setTestResult("Passed");
            }
            if (stmt != null)
                try {
                    stmt.close();
                } catch (SQLException e) {
                    log.error(e);
                }
            if (minusPWriter != null)
                minusPWriter.close();
            if (rowCount == 0 && compare.isFileOutputOn())
                diffFileName.delete();
        }
    }

    private String getMinusQuery(Datasource datasource, String sql1, String sql2,  TestParams testParams) {
        String sqlMinus = " MINUS ";
        Compare compare = testParams.getCmpSqlResultsTest().getCompare();
        CmpSqlResultsTest cmpSqlResultsTest = testParams.getCmpSqlResultsTest();
        String firstMinusQueryIndicatorText;
        String secondMinusQueryIndicatorText;

        //default query indicator for SQL1
        if(cmpSqlResultsTest.getCompare().getSqls().get(0).getMinusQueryIndicatorText()== null
                || cmpSqlResultsTest.getCompare().getSqls().get(0).getMinusQueryIndicatorText().isEmpty()){
            firstMinusQueryIndicatorText= ",'query1' as \"Environment\" \n From ";
            cmpSqlResultsTest.getCompare().getSqls().get(0).setMinusQueryIndicatorText("query1");
        }
        else {
            firstMinusQueryIndicatorText=",'"+cmpSqlResultsTest.getCompare().getSqls().get(0).getMinusQueryIndicatorText()+"' as \"Environment\" \n From ";
        }

        //default query indicator for SQL2
        if(cmpSqlResultsTest.getCompare().getSqls().get(1).getMinusQueryIndicatorText()== null
                || cmpSqlResultsTest.getCompare().getSqls().get(0).getMinusQueryIndicatorText().isEmpty()) {
            secondMinusQueryIndicatorText = ",'query2' as \"Environment\" \n From ";
            cmpSqlResultsTest.getCompare().getSqls().get(1).setMinusQueryIndicatorText("query2");
        }
        else {
            secondMinusQueryIndicatorText=",'"+cmpSqlResultsTest.getCompare().getSqls().get(1).getMinusQueryIndicatorText()+"' as \"Environment\" \n From ";
        }

        // TODO check if other than SQLServerDriver databases has somethings else (instead of MINUS)
        if (datasource.getDriver().contains("SQLServerDriver") || datasource.getDriver().contains("postgresql")) {
            sqlMinus = " EXCEPT ";
        }
        else if (datasource.getDriver().contains("teradata")) {
            sqlMinus = " MINUS ALL ";
        }
        StringBuffer sqlStrBuff = new StringBuffer("(");

        if(compare.isMinusQueryIndicatorOn() && compare.getDiffTableSize() > 0){;

            sqlStrBuff.append(
                    replaceNthIndexOf(sql1, cmpSqlResultsTest.getCompare().getSqls().get(0).getMinusQueryIndicatorOccurence(), firstMinusQueryIndicatorText) +
                    sqlMinus +
                    replaceNthIndexOf(sql2, cmpSqlResultsTest.getCompare().getSqls().get(1).getMinusQueryIndicatorOccurence(), firstMinusQueryIndicatorText)
            );
        } else {
            sqlStrBuff.append(sql1).append(sqlMinus).append(sql2);
        }

        sqlStrBuff.append(")");
        sqlStrBuff.append(" UNION ALL ");
        sqlStrBuff.append("(");

        if(compare.isMinusQueryIndicatorOn() && compare.getDiffTableSize() > 0) {
            sqlStrBuff.append(
                    replaceNthIndexOf(sql2, cmpSqlResultsTest.getCompare().getSqls().get(1).getMinusQueryIndicatorOccurence(), secondMinusQueryIndicatorText) +
                    sqlMinus +
                    replaceNthIndexOf(sql1, cmpSqlResultsTest.getCompare().getSqls().get(0).getMinusQueryIndicatorOccurence(), secondMinusQueryIndicatorText)
            );
        } else {
            sqlStrBuff.append(sql2).append(sqlMinus).append(sql1);
        }
        sqlStrBuff.append(")");
        return sqlStrBuff.toString();

    }
    //add tuple source just before nth "FROM" (case-insensitive)
    public static String replaceNthIndexOf(String str, String occurrence, String replace)
            throws IndexOutOfBoundsException {

        int index = -1;
        String regex = "from";
        Pattern p = Pattern.compile(regex, Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(str);

        //check which occurrence of FROM should be replaced, or is it all occurrences?
        try {
            int intOccurrence = Integer.parseInt(occurrence.trim());
            while(m.find()) {
                if (--intOccurrence == 0) {
                    index = m.start();
                    break;
                }
            }
            if (index < 0) throw new IndexOutOfBoundsException();
            return  str.substring(0, index)+ replace + str.substring(index+ 4, str.length());
        } catch (NumberFormatException nfe) { //let's assume not-a-number means "*"
            return str.replaceAll("(?i)FROM", replace );
        }

    }

    private String getCountQuery(String minusSqlQuery) {
        StringBuffer sqlStrBuff = new StringBuffer("select COUNT(*) from ( ").append(minusSqlQuery).append(
                " ) countTable");
        return sqlStrBuff.toString();
    }

}

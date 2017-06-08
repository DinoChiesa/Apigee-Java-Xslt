package com.google.apigee.edgecallouts.xslt;

import javax.xml.transform.ErrorListener;
import javax.xml.transform.TransformerException;

import com.apigee.flow.message.MessageContext;

public class CustomXsltErrorListener implements javax.xml.transform.ErrorListener {
    private final static String _prefix = "xslt_";
    MessageContext _msgCtxt;
    int _warnCount;
    int _errorCount;
    boolean _debug = false;
    private static String varName(String s) {
        return _prefix + s;
    }

    public CustomXsltErrorListener(MessageContext msgCtxt) {
        _msgCtxt = msgCtxt;
        _warnCount = 0;
        _errorCount = 0;
    }
    public CustomXsltErrorListener(MessageContext msgCtxt, boolean debug) {
        _msgCtxt = msgCtxt;
        _warnCount = 0;
        _errorCount = 0;
        _debug = debug;
    }
    public void error(TransformerException exception) {
        _errorCount++;
        if (_debug) {
            System.out.printf("Error\n");
            exception.printStackTrace();
        }
        _msgCtxt.setVariable(varName("error_" + _errorCount), "Error:" + exception.toString());
    }
    public void fatalError(TransformerException exception) {
        _errorCount++;
        if (_debug) {
            System.out.printf("Fatal\n");
            exception.printStackTrace();
        }
        _msgCtxt.setVariable(varName("error_" + _errorCount), "Fatal Error:" + exception.toString());
    }

    public void warning(TransformerException exception) {
        _warnCount++;
        if (_debug) {
            System.out.printf("Warning\n");
            exception.printStackTrace();
        }
        _msgCtxt.setVariable(varName("warning_" + _warnCount), "Warning:" + exception.toString());
    }

    public int getErrorCount() {
        return _errorCount;
    }
}

package org.orbisoftware.browser;

import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.BrowserFunction;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.gjt.sp.jedit.jEdit;

public class SWTBrowser {

	private jEdit jeditMainApplicaion;
	
    public static void main(String[] args) {

    	SWTBrowser swtBrowser = new SWTBrowser();
    	
    	swtBrowser.jeditMainApplicaion = new jEdit();
    	
        Display display = new Display();
        Shell shell = new Shell(display);
        shell.setLayout(new FillLayout());

        // Create a Browser widget
        Browser browser = new Browser(shell, SWT.EDGE);

        // Load a URL
        browser.setUrl("http://127.0.0.1:8000/content.html");
        browser.refresh();
        
        shell.setSize(800, 600);
        shell.open();
        
        Thread thread = new Thread(swtBrowser.jeditMainApplicaion);
        thread.start();
        
        // Create a BrowserFunction to handle the JavaScript call
        new BrowserFunction(browser, "scrollToFOMLineHandler") {
          @Override
          public Object function(Object[] args) {
        	
        	int lineNumber = 0;
            String lineNumberStr = (String) args[0];
            String innerHtmlStr = (String) args[1];
            
            try {
            	lineNumber = Integer.parseInt(lineNumberStr) - 1 ;
            	
            	if (lineNumber < 1)
            		lineNumber = 1;
            	
            	swtBrowser.jeditMainApplicaion.scrollToLineCentered(lineNumber);
                
                // Perform the desired action in Java
                //System.err.println("Mouse over element: " + lineNumberStr + " with content: " + innerHtmlStr);
            } catch (Exception e) {}

            return null; // Return value to JavaScript (optional)
          }
        };
        
        while (!shell.isDisposed()) {
            if (!display.readAndDispatch()) {
                display.sleep();
            }
        }
        display.dispose();
    }
}
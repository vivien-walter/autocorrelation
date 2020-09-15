package multi_plot;
import java.awt.*;
import java.awt.image.*;
import java.awt.event.*;
import java.io.*;
import java.awt.datatransfer.*;
import java.util.*;
import ij.*;
import ij.process.*;
import ij.util.*;
import ij.text.*;
import ij.plugin.filter.*;
import ij.gui.*;
import ij.measure.*;


/*	MultyPlotWindowExt for displaying of multiple lines plot
 *	It is based largely on the PlotWindow class
 *	However it provides additional functionality like changing the line headings
 *	setting of the decimal precision different from the global precision setting
 *	proper handling of the EOL character
 *	@version 1.0;  02 Oct 2004
 *
 *	@author	Dimiter Prodanov
 *	@author  University of Leiden
 *
 *	This library is free software; you can redistribute it and/or
 *	modify it under the terms of the GNU Lesser General Public
 *	License as published by the Free Software Foundation; either
 *	version 2.1 of the License, or (at your option) any later version.
 *
 *	This library is distributed in the hope that it will be useful,
 *	but WITHOUT ANY WARRANTY; without even the implied warranty of
 *	MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 *	Lesser General Public License for more details.
 *
 *	You should have received a copy of the GNU Lesser General Public
 *	License along with this library; if not, write to the Free Software
 *	Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

public class MultyPlotWindowExt extends ImageWindow implements ActionListener, ClipboardOwner {

	/** Display points using a circle 5 pixels in diameter. */
	public static final int CIRCLE = 0;
	/** Display points using an X-shaped mark. */
	public static final int X = 1;
	/** Display points using an box-shaped mark. */
	public static final int BOX = 3;
	/** Display points using an tiangular mark. */
	public static final int TRIANGLE = 4;
	/** Display points using an cross-shaped mark. */
	public static final int CROSS = 5;
	/** Connect points with solid lines. */
	public static final int LINE = 2;

	private static final int LEFT_MARGIN = 50;
	private static final int RIGHT_MARGIN = 20;
	private static final int TOP_MARGIN = 20;
	private static final int BOTTOM_MARGIN = 30;
	private static final int WIDTH = 450;
	private static final int HEIGHT = 200;

	private static final String MIN = "pp.min";
	private static final String MAX = "pp.max";
	private static final String PLOT_WIDTH = "pp.width";
	private static final String PLOT_HEIGHT = "pp.height";
	private static final String OPTIONS = "pp.options";
	private static final int SAVE_X_VALUES = 1;
	private static final int AUTO_CLOSE = 2;
	private static final int LIST_VALUES = 4;

	private Button list, save, copy, fit;
	private Label coordinates;
	private static String defaultDirectory = null;
	private Font font = new Font("Helvetica", Font.PLAIN, 12);
	private static int options;
	private int defaultDigits = 2;
	private boolean realNumbers;
	private int xdigits, ydigits;
	private int markSize = 5;
	private static MultyPlotExt staticPlot;
	private MultyPlotExt plot;
	private String lineHeadings="";

	/** Save x-values only. To set, use Edit/Options/
	    Profile Plot Options. */
	public static boolean saveXValues;
    
	/** Automatically close window after saving values. To
	    set, use Edit/Options/Profile Plot Options. */
	public static boolean autoClose;
    
	/** The width of the plot in pixels. */
	public static int plotWidth = WIDTH;

	/** The height of the plot in pixels. */
	public static int plotHeight = HEIGHT;
    
	/** Display the XY coordinates in a separate window. To
	    set, use Edit/Options/Profile Plot Options. */
	public static boolean listValues;
    
	// static initializer
	static {
		IJ.register(MultyPlotWindowExt.class); //keeps options from being reset on some JVMs
		options     = Prefs.getInt(OPTIONS, SAVE_X_VALUES);
//		saveXValues = (options&SAVE_X_VALUES)!=0;
		saveXValues = true;
		autoClose   = (options&AUTO_CLOSE)!=0;
		listValues  = (options&LIST_VALUES)!=0;
		plotWidth   = Prefs.getInt(PLOT_WIDTH, WIDTH);
		plotHeight  = Prefs.getInt(PLOT_HEIGHT, HEIGHT);
	}
    
	/** Construct a new PlotWindow.
	  * @param title			the window title
	  * @param xLabel			the x-axis label
	  * @param yLabel			the y-axis label
	  * @param xValues		the x-coodinates
	  * @param yValues		the y-coodinates
	*/
	public MultyPlotWindowExt(String title, String xLabel, String yLabel, float[] xValues, float[] yValues) {
		super(createImage(title, xLabel, yLabel, xValues, yValues));
		plot = staticPlot;
	}

	/** This version of the constructor excepts double arrays. */
	public MultyPlotWindowExt(String title, String xLabel, String yLabel, double[] xValues, double[] yValues) {
		this(title, xLabel, yLabel, Tools.toFloat(xValues), Tools.toFloat(yValues));
		initDigits();
	}

	/** Creates a PlotWindow from a Plot object. */
	MultyPlotWindowExt(MultyPlotExt plot) {
		super(plot.getImagePlus());
		this.plot = plot;
		initDigits();
		draw();
	}
    
	/** Called by the constructor to generate the image the plot will be drawn on.
	    This is a static method because constructors cannot call instance methods. */
	static ImagePlus createImage(String title, String xLabel, String yLabel, float[] xValues, float[] yValues) {
		staticPlot = new MultyPlotExt(title, xLabel, yLabel, xValues, yValues);
		return new ImagePlus(title, staticPlot.getBlankProcessor());
	}
    
	/** Sets the x-axis and y-axis range. */
	public void setLimits(double xMin, double xMax, double yMin, double yMax) {
		plot.setLimits(xMin, xMax, yMin, yMax);
	}
    
	/** Adds a set of points to the plot or adds a curve if shape is set to LINE.
	  * @param x			the x-coodinates
	  * @param y			the y-coodinates
	  * @param shape		CIRCLE, X, BOX, TRIANGLE, CROSS or LINE
	*/
	public void addPoints(float[] x, float[] y, int shape) {
		plot.addPoints(x, y, shape);
	}

	/** Adds a set of points to the plot using double arrays.
	    Must be called before the plot is displayed. */
	public void addPoints(double[] x, double[] y, int shape) {
		addPoints(Tools.toFloat(x), Tools.toFloat(y), shape);
	}

	/** Adds error bars to the plot. */
	public void addErrorBars(float[] errorBars) {
		plot.addErrorBars(errorBars);
	}

	/** Draws a label. */
	public void addLabel(double x, double y, String label) {
		plot.addLabel(x, y, label);
	}
    
	/** Changes the drawing color. The frame and labels are
	    always drawn in black. */
	public void setColor(Color c) {
		plot.setColor(c);
	}
    
	/** Changes the line width. */
	public void setLineWidth(int lineWidth) {
		plot.setLineWidth(lineWidth);
	}

	public void setLineHeadings(String[] headings, boolean ErrorBars) {
		StringBuffer sb=new StringBuffer();
		for (int j=0;j<headings.length;j++) {
			if (!ErrorBars)
				sb.append("\t"+headings[j]);
			else
				sb.append("\t"+headings[j]+"\tErrorBar");
		}
		lineHeadings=sb.substring(1);
	}

	public String getLineHeadings() {
		return lineHeadings;
	}

	/** Changes the font. */
	public void changeFont(Font font) {
		plot.changeFont(font);
	}

	/** Displays the plot. */
	public void draw() {
		Panel buttons = new Panel();
		buttons.setLayout(new FlowLayout(FlowLayout.RIGHT));
		list = new Button(" List ");
		list.addActionListener(this);
		buttons.add(list);
		save = new Button("Save...");
		save.addActionListener(this);
		buttons.add(save);
		copy = new Button("Copy...");
		copy.addActionListener(this);
		buttons.add(copy);
		fit=new Button("Fit");
		fit.addActionListener(this);
		buttons.add(fit);
		coordinates = new Label("                     ");
		coordinates.setFont(new Font("Monospaced", Font.PLAIN, 12));
		buttons.add(coordinates);
		add(buttons);
		plot.draw();
		pack();
		ImageProcessor ip = plot.getProcessor();
		if ((ip instanceof ColorProcessor) && (imp.getProcessor() instanceof ByteProcessor))
			imp.setProcessor(null, ip);
		else
			imp.updateAndDraw();
//		IJ.log("listValues "+listValues);
		if (listValues)
			showList();
	}
    
	int getDigits(double n1, double n2) {
		if (Math.round(n1)==n1 && Math.round(n2)==n2)
			return 0;
		else {
			n1 = Math.abs(n1);
			n2 = Math.abs(n2);
			double n = n1<n2&&n1>0.0?n1:n2;
			double diff = Math.abs(n2-n1);
			if (diff>0.0 && diff<n) n = diff;
			int digits = 1;
			if (n<10.0) digits = 4;
			if (n<0.01) digits = 5;
			if (n<0.001) digits = 6;
			if (n<0.0001) digits = 7;
			return digits;
		}
	}
    
	/** Updates the graph X and Y values when the mouse is moved.
	    Overrides mouseMoved() in ImageWindow.
	    @see ij.gui.ImageWindow#mouseMoved
	*/
	public void mouseMoved(int x, int y) {
		if (plot!=null && plot.frame!=null && coordinates!=null)
			coordinates.setText(plot.getCoordinates(x,y));
	}

	void showList() {
//		IJ.log("Plot Values");
		TextWindow tw = new TextWindow("Plot Values - "+plot.title, this.getLineHeadings(), this.toString("\n"), 200, 400);
		if (autoClose) {
			imp.changes=false;
			close();
		}
	}

	final static String EOL	= System.getProperty("line.separator");

	/* Work qround for the TextWindow bug that prevents properly displaying the EOL characters
	 * on Windows
	*/
	public String toString(String eol) {
		StringBuffer sb = new StringBuffer();
//		String headings;
//		String h=getLineHeadings();
//		initDigits();
		float[] xv=plot.getXValues();
		float[][] yv=plot.getYValues();
		float[][] eb=plot.getErrorBars();
		if (!plot.errorBars.isEmpty()) {
			for (int i=0; i<plot.nPoints; i++) {
				StringBuffer sb2 = new StringBuffer();
				if (saveXValues) {
					for (int j=0;j<yv.length;j++) {
						sb2.append("\t"+IJ.d2s(yv[j][i],ydigits)+"\t"+IJ.d2s(eb[j][i],ydigits));
					}
					sb.append(IJ.d2s(xv[i],xdigits));
					sb.append(sb2);
					sb.append(eol);
				} else {
					for (int j=0;j<yv.length;j++) {
						sb2.append("\t"+IJ.d2s(yv[j][i],ydigits));
					}
					sb.append(IJ.d2s(xv[i],xdigits));
					sb.append(sb2);
					sb.append(eol);
				}
			} // end for
		} else {
			for (int i=0; i<plot.nPoints; i++) {
				StringBuffer sb2 = new StringBuffer();
				for (int j=0;j<yv.length;j++) {
					sb2.append("\t"+IJ.d2s(yv[j][i],ydigits));
				}
				if (saveXValues) {
					sb.append(IJ.d2s(xv[i],xdigits));
					sb.append(sb2);
					sb.append(eol);
				} else {
					sb.append(sb2);
					sb.append(eol);
				}
			} //end for
		}
		return sb.toString();
	}

	void saveAsText() {
		FileDialog fd = new FileDialog(this, "Save as Text...", FileDialog.SAVE);
		if (defaultDirectory!=null)
			fd.setDirectory(defaultDirectory);
		fd.setFile(plot.title);
//		fd.setFile(plot.title.substring(22, plot.title.length()));
		fd.show();
		String name = fd.getFile();
		String directory = fd.getDirectory();
		defaultDirectory = directory;
		fd.dispose();
		PrintWriter pw = null;
		try {
			FileOutputStream fos = new FileOutputStream(directory+name);
			BufferedOutputStream bos = new BufferedOutputStream(fos);
			pw = new PrintWriter(bos);
		}
		catch (IOException e) {
			IJ.error("" + e);
			return;
		}
		IJ.wait(250);  // give system time to redraw ImageJ window
		IJ.showStatus("Saving plot values...");
//		initDigits();
//		for (int i=0; i<plot.nPoints; i++) {
//			if (saveXValues)
//				pw.println(IJ.d2s(plot.xValues[i],xdigits)+"\t"+IJ.d2s(plot.yValues[i],ydigits));
//			else
//				pw.println(IJ.d2s(plot.yValues[i],ydigits));
//		}
		pw.print(this.getLineHeadings());
		pw.print(EOL);
		pw.print(this.toString(EOL));
		pw.close();
		if (autoClose) {
			imp.changes=false;
			close();
		}
	}
    
	void copyToClipboard() {
		Clipboard systemClipboard = null;
		try {systemClipboard = getToolkit().getSystemClipboard();}
		catch (Exception e) {
			systemClipboard = null;
		}
		if (systemClipboard==null) {
			IJ.error("Unable to copy to Clipboard.");
			return;
		}
		IJ.showStatus("Copying plot values...");
//		initDigits();
		CharArrayWriter aw = new CharArrayWriter(plot.nPoints*4);
		PrintWriter pw = new PrintWriter(aw);
//		for (int i=0; i<plot.nPoints; i++) {
//			if (saveXValues)
//				pw.print(IJ.d2s(plot.xValues[i],xdigits)+"\t"+IJ.d2s(plot.yValues[i],ydigits)+"\n");
//			else
//				pw.print(IJ.d2s(plot.yValues[i],ydigits)+"\n");
//		}
		pw.print(this.getLineHeadings());
		pw.print("\n");
		pw.print(this.toString("\n"));
//		pw.print(this.toString(EOL));
		String text = aw.toString();
		pw.close();
		StringSelection contents = new StringSelection(text);
		systemClipboard.setContents(contents, this);
		IJ.showStatus(text.length() + " characters copied to Clipboard");
		if (autoClose) {
			imp.changes=false;
			close();
		}
	}

	public void setPrecision(int xd, int yd) {
		xdigits = xd;
		ydigits = yd;
		defaultDigits = ydigits;
	}

	void initDigits() {
		ydigits = Analyzer.getPrecision();
		if (ydigits==0)
			ydigits = 2;
		if (ydigits!=defaultDigits) {
			realNumbers = false;
			for (int i=0; i<plot.xValues.length; i++) {
				if ((int)plot.xValues[i]!=plot.xValues[i])
					realNumbers = true;
			}
			defaultDigits = ydigits;
		}
		ydigits = 4; //update by André SCHRODER
		defaultDigits = ydigits; //update by André SCHRODER
		xdigits =  realNumbers?ydigits:0;
		xdigits = 4; //update by André SCHRODER
	}

	public void lostOwnership(Clipboard clipboard, Transferable contents) {}

	public void actionPerformed(ActionEvent e) {
		Object b = e.getSource();
		if (b.equals(list))  showList();
		if (b.equals(save)) saveAsText();
		if (b.equals(fit)) doFit(cf.STRAIGHT_LINE);
		if (b.equals(copy)) copyToClipboard();
	}

	static CurveFitter cf;

	private void doFit(int type) {
		float[][] z=plot.getYValues();
		double[] x=Tools.toDouble(plot.getXValues());
		for (int i=0; i<z.length;i++) {
			double[] y=Tools.toDouble(z[i]);
			cf = new CurveFitter(x, y);
//			cf.setMaxIterations(100);
//			cf.setRestarts(2);
			cf.doFit(type);
			IJ.log(cf.getResultString());
		}
	}

	public float[] getXValues() {
		return plot.xValues;
	}

	public float[][] getYValues() {
		return plot.getYValues();
	}

	/** Draws a new plot in this window. */
	public void drawPlot(MultyPlotExt plot) {
		this.plot = plot;
		imp.setProcessor(null, plot.getProcessor());
	}
    
	/** Called once when ImageJ quits. */
	public static void savePreferences(Properties prefs) {
		double min = ProfilePlot.getFixedMin();
		double max = ProfilePlot.getFixedMax();
		if (!(min==0.0&&max==0.0) && min<max) {
			prefs.put(MIN, Double.toString(min));
			prefs.put(MAX, Double.toString(max));
		}
		if (plotWidth!=WIDTH || plotHeight!=HEIGHT) {
			prefs.put(PLOT_WIDTH, Integer.toString(plotWidth));
			prefs.put(PLOT_HEIGHT, Integer.toString(plotHeight));
		}
		int options = 0;
		if (saveXValues)
			options |= SAVE_X_VALUES;
		if (autoClose && !listValues)
			options |= AUTO_CLOSE;
		if (listValues)
			options |= LIST_VALUES;
		prefs.put(OPTIONS, Integer.toString(options));
	}

}
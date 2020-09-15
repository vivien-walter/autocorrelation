import ij.*;
import ij.gui.*;
import ij.util.*;
import ij.plugin.filter.*;
import ij.plugin.filter.PlugInFilter;
import ij.plugin.frame.RoiManager;
import ij.process.*;
import ij.measure.*;
import ij.measure.Calibration;
import ij.text.*;
import ij.macro.Functions;
import ij.macro.MacroExtension;
import ij.macro.ExtensionDescriptor;
import java.awt.*;
import java.awt.event.*;
import java.awt.TextField;
import java.util.*;
import multi_plot.*;

//###########################################################################################################
//###########################################################################################################
//###########################################################################################################

/** DESCRIPTION OF THE PLUG-IN
 *
 *  This plugin produces a plot of the mean AutoCorrelation Function (ACF) of every pixel
 *	intensity over time (in a stack) or the radial averaged AutoCorrelation Function for one
 *	picture or for a whole stack.
 *	The plugin performs the calculation inside an circle ROI defined by the user.
 *
 *	The plugin implements several options for the calculation of the radial ACF:
 *	- For one picture, the calculation can be done using the FFT (faster) or with the naive
 *	autocorrelation function (slower, but more accurate). When calculating over a stack, only
 *	the FFT method is available.
 *	- User can decide if the radius should be converted into pixel or scale set for the picture.
 *	- For the calculation of the radial ACF on a stack, the result plotted can be the ACF of every
 *	slice of the stack, or the mean ACF of the whole stack.
 *	For pixels' ACF over time, the plugin can return the evolution of intensity of every pixel over time.
 *
 *  This plugin is a mod of the Radial Profile Extended plugin from Philippe CARL and use
 *	most of the features implemented in it.
 *  http://rsb.info.nih.gov/ij/plugins/radial-profile-ext.html
 *
 *	The Radial averaged Autocorrelation calculation using the FFT is based on the macro
 *	Radially Averaged Autocorrelation from Michael SCHMID
 *	http://imagejdocu.tudor.lu/doku.php?id=macro:radially_averaged_autocorrelation
 *  
 *  Current version: (alpha)
 *  Release date: XX-XX-XXXX
 *  Author: Vivien WALTER (walter.vivien@gmail.fr)
*/

//###########################################################################################################
//###########################################################################################################
//###########################################################################################################

/** MAP OF THE CODE
 *
 * FUNCTIONS FOR THE PLUG-IN INITIALISATION
 * - Start and initialize the plug-in
 *
 * FUNCTIONS FOR THE AUTOCORRELATION CALCULATIONS
 * - All the main functions used for the autocorrelation calculations
 *
 * GENERIC MATHEMATICAL FUNCTIONS
 * - Mathematical functions for calculations within the plug-in
 * 
 * LISTENERS USED BY THE PLUG-IN FOR GUINTERFACING
 * - List of all the listeners used in the plug-in for using keyboard and mouse control
 *
 * DESCRIPTION OF THE PLUGIN
 * - Description of the plugin to be displayed in ImageJ 'About...' section
 *
*/

//###########################################################################################################
//###########################################################################################################
//###########################################################################################################

// MAIN CODE OF THE PLUGIN

public class AutoCorrelation_Function implements PlugInFilter, ImageListener, ActionListener, KeyListener, DialogListener, MouseMotionListener, MouseWheelListener, Measurements, MacroExtension
{
	private static AutoCorrelation_Function instance;
	private final static int X_CENTER = 0, Y_CENTER = 1, RADIUS = 2, START_ANGLE = 3, INT_ANGLE = 4;
	ImagePlus imp;
	ImageProcessor ip2;
	ImageCanvas canvas;
	Rectangle rct;
	MultyPlotExt plot, plotInt;
	MultyPlotExtScN plotAmp;
	ShapeRoi s1, s2;
	Overlay overlay;
	NonBlockingGenericDialog gd;
	boolean previousRequireControlKeyState;
	String[] type = {"ACF on Pixels","ACF on Area"};
	String[] shapeRoi = {"Circle","Square"};
	String[] stepType = {"Power of 2", "Linear", "Inverse"};
	String[] stepList = {"2%", "4%", "5%", "10%", "20%", "25%", "50%"};
	String waveStep, typeStep;
	static boolean useFFT			 = true;
	static boolean useCalibration    = true;
	static boolean useTimeCalibration = true;
	static boolean makeStackAnalysis = false;
	static boolean makeStackmean	 = false;
	static boolean displayAmplitude	 = false;
	static boolean displayFilter	 = false;
	static boolean debugMode		 = false;
	static boolean smoothBandpass	 = false;
	String typeACF = null;
	String roiShape = null;
	static int shift_button_mask  = InputEvent.SHIFT_DOWN_MASK | InputEvent.BUTTON1_DOWN_MASK;
	static int ctrl_mask          = InputEvent.CTRL_DOWN_MASK;
	static int  alt_mask          = InputEvent. ALT_DOWN_MASK;
	static int  alt_shift_mask    = InputEvent. ALT_DOWN_MASK + InputEvent.SHIFT_DOWN_MASK;
	static int  alt_ctrl_mask     = InputEvent. ALT_DOWN_MASK + InputEvent.CTRL_DOWN_MASK;
	int nBins = 100;
	int resolWav = 25;
	int displayFiltNumber = 1;
	int[]   xPoint = new int[6];
	int[]   yPoint = new int[6];
	double X0;		// X center in pixels of the circle over which the calculation is done
	double Y0;		// Y center in pixels of the circle over which the calculation is done
	double mR;		// Radius in pixels of the circle over which the calculation is done
	float []   dataX;	// X data of the plot
	float [][] dataY;	// Y data of the plot
	TextField[] numericFields;
	Button button0, button1, button2;
	CheckboxGroup cbg;
	Checkbox cb0, cb1, cb2, cb3, cb4, cb5, cb6, cb7, cb8;
	Scrollbar slider0;
	Choice choice0, choice1;

	//------------------------------------------##
	// FUNCTIONS FOR THE PLUG-IN INITIALISATION ##
	//------------------------------------------##

	public int setup(String arg, ImagePlus imp)
	{
		if (arg.equals("about"))
		{
			showAbout();
			return DONE;
		}
		if (IJ.versionLessThan("1.48p"))
			return DONE;

		this.imp = imp;

		if (instance != null && instance.getDialog() != null)
		{
			instance.getDialog().toFront();
			ImageWindow win = instance.getImagePlus().getWindow();
			if (win != null) win.toFront();
			return DONE;
		} else
			instance = this;

		if (instance.getImagePlus() != null && instance.getImagePlus().getWindow() != null)
			instance.getImagePlus().getWindow().toFront();

		return DOES_ALL + NO_UNDO + NO_CHANGES;
	}

	public void run(ImageProcessor ip)
	{
		imp.unlock();
		ip2 = ip;
		ImageWindow win = imp.getWindow();
		win.addKeyListener(this);
		ImagePlus.addImageListener(this); 
		canvas = win.getCanvas();
		canvas.addKeyListener(this);
		canvas.addMouseMotionListener(this);
		canvas.addMouseWheelListener(this);
		previousRequireControlKeyState = Prefs.requireControlKey;
		Prefs.requireControlKey = true;
		setXYcenter();

		if (!getParams())
		{
			removeListeners(imp);
			return;
		}

		try
		{
			//calculateRadialAutoCorrelation();
		}
		catch (Exception e)
		{
			IJ.error(e.getMessage());
		}

		if (IJ.macroRunning())
			Functions.registerExtensions(this);

		imp.setOverlay(null);
		removeListeners(imp);
	}

	public String getImageTitle()
	{
		String str = imp.getTitle();
		int len = str.length();
		if (len > 4 && str.charAt(len - 4) == '.' && !Character.isDigit(str.charAt(len - 1)))
			return str.substring(0, len - 4);  
		else
			return str;
	}
	
	public void setXYcenter()
	{
		if(imp.getRoi() == null)
		{
			X0 = canvas.getWidth() / 2.0;
			Y0 = canvas.getHeight() / 2.0;
			mR = (X0 + Y0) / 2.0;
		}
		else
		{
			rct = imp.getRoi().getBounds();
			X0 = (double) rct.x + (double) rct.width / 2;
			Y0 = (double) rct.y + (double) rct.height / 2;
			mR = (rct.width + rct.height) / 4.0;
		}
	}


	private NonBlockingGenericDialog getDialog()
	{
		return gd;
	}


	private ImagePlus getImagePlus()
	{
		return imp;
	}
	
	//------------------------------------------------##
	// FUNCTIONS FOR THE AUTOCORRELATION CALCULATIONS ##
	//------------------------------------------------##
	
	// SELECT THE TYPE OF SPATIAL ACF TO CALCULATE
	public void calculateRadialAutoCorrelation()
	{
		if (imp.getStackSize() > 1)
		{
			makeStackAnalysis = cb3.getState();
		}
		else
		{
			makeStackAnalysis = false;
		}
	
		if(makeStackAnalysis)
		{
			useCalibration = cb1.getState();
			doStackAutoCorrelation();
		}
		else
		{
			useCalibration = cb1.getState();
			doRadialAutoCorrelation(ip2);
		}
	}

	// SELECT THE TYPE OF TIME ACF TO CALCULATE
	private void calculateOverTime()
	{
		if (imp.getStackSize() > 1)
		{
		
			if (typeACF == "ACF on Pixels")
			{
				useCalibration = cb1.getState();
				AutocorrTime();
			}
			else
			{
				useCalibration = cb1.getState();
				AutocorrTimeArea();
			}
		}
		else
			IJ.showMessage("Error", "Stack required");
	}

	// CALCULATE THE ACF OVER WAVELENGTH
	private void testWaveACF()
	{
		if (imp.getStackSize() > 1)
		{
			useCalibration = cb1.getState();
			WavelengthACF();
		}
		else
			IJ.showMessage("Error", "Stack required");
	}

	// CALCULATE THE SPATIAL AUTOCORRELATION FUNCTION ON A SINGLE FRAME
	public void doRadialAutoCorrelation(ImageProcessor ip)
	{

		IJ.log("Start spatial ACF calculation on a single picture");

		nBins = (int) (3*mR/4);
		dataX  = new float   [nBins];
		dataY  = new float[1][nBins];

		int i;
		int thisBin;
		int xOff, yOff;

		double a, b, c, d;
		double R, Rscan;
		double xmin = X0 - mR, xmax = X0 + mR, ymin = Y0 - mR, ymax = Y0 + mR;
		double xminscan, xmaxscan, yminscan, ymaxscan;
		double minFFTsize = 2 * mR, FFTsize = 4;
		double mean;

		String unitName;
	        String[] headings = new String[2];
		
		//DEBUG INITIALIZATION
		boolean debugDisplay = false;
		if(debugMode)
		{
			debugDisplay = true; //Only display the very first cropped picture in debug mode
		}
		
		OvalRoi oroi = new OvalRoi(xmin, ymin, 2 * mR, 2 * mR); //Set a new ROI with the same size and shape that the one set by the user
		imp.setRoi(oroi); //Set the ROI on the ImagePlus
		mean = imp.getStatistics().mean; //Calculate the mean of intensity in the ROI
		
		//AUTOCORRELATION THROUGH FFT/FHT (From "FD Math...")
		if (useFFT)
		{
			IJ.log("Perform the calculation with FFT");
			dataY[0] = performFourierACF(imp);
		}

		//AUTOCORRELATION WITHOUT FFT/FHT
		else
		{
			IJ.log("Perform the calculation without FFT");
			dataY[0] = getRadialProfile(ip, xmin, xmax, ymin, ymax, X0, Y0, mean, useFFT);
		}

		IJ.log("Value retrieved. Calculating the spatial calibration");
		
		Calibration cal = imp.getCalibration();
		if (cal == null || cal.getUnit() == "pixel")
			useCalibration = false;

		// GENERATE THE DATAX ARRAY REGARDING THE SPATIAL CALIBRATION OF THE PICTURE
		if (useCalibration)
		{
			if(cal.pixelWidth != cal.pixelHeight)
			{
				cal.pixelWidth = ( cal.pixelWidth + cal.pixelHeight ) / 2;
				IJ.showMessage("WARNING", "Pixel Width is different from pixel\n" +
				"Height in spatial calibration!\n" +
				"Average pixel length used instead.");
			}
		
			for (i = 0; i < nBins; i++)
			{
				dataX[i]    = (float) (cal.pixelWidth * mR * ((double)(i + 1) / nBins));
			}

			unitName = cal.getUnits();

		}
		else
		{
			for (i = 0; i < nBins; i++)
			{
				dataX[i]    = (float) (mR * ((double) (i + 1) / nBins));
			}

			unitName = "pixels";
		}

		IJ.log("Display the results in a graph");

		// DISPLAY THE RESULTING GRAPHIC
		plot = new MultyPlotExt("AutoCorrelation on "+getImageTitle()+"", "Radius ["+unitName+"]", "AutoCorrelation",  dataX, dataY[0]);
		headings[0] = "Radius ["+unitName+"]\t";
		headings[1] = "AutoCorrelation";
	        MultyPlotWindowExt wnd = plot.show();
        	wnd.setLineHeadings(headings, false);
	}

	// CALCULATE THE SPATIAL AUTOCORRELATION FUNCTION ON A SET OF FRAMES
	public void doStackAutoCorrelation()
	{
		nBins = (int) (3*mR/4);
		dataX  = new float[nBins];
		dataY  = new float[imp.getStackSize()][nBins];
		int i, j;
		int thisBin;
	    String[] headings = new String[imp.getStackSize() + 1];
		int xOff, yOff;
		float minY, maxY;
		double[] extrema;
		double a, b;
		double R;
		double xmin = X0 - mR, xmax = X0 + mR, ymin = Y0 - mR, ymax = Y0 + mR;
		double minFFTsize = 2 * mR, FFTsize = 4;
		double mean;

		OvalRoi oroi = new OvalRoi(xmin, ymin, 2 * mR, 2 * mR);

		String unitName;
		
		//DEBUG INITIALIZATION
		boolean debugDisplay = false;
		if(debugMode)
			debugDisplay = true; //Only display the very first cropped picture in debug mode
		
		Calibration cal = imp.getCalibration();
		if (cal == null || cal.getUnit() == "pixel")
			useCalibration = false;
		if (cal == null || cal.frameInterval == 0)
			useTimeCalibration = false;

		// Measure the ACF on all the pictures of the stack
		for (j = 0; j != imp.getStackSize(); j++)
		{
			// Move to the new slice
			imp.setSlice(j + 1);			
			if (useTimeCalibration)
				headings[j+1] =  ""+ IJ.d2s((j * cal.frameInterval), getDigits(j * cal.frameInterval, j * cal.frameInterval)) + " " + cal.getTimeUnit();
			else
				headings[j+1] =  String.valueOf(j+1);

			//AUTOCORRELATION THROUGH FFT/FHT (From "FD Math...")
			if (useFFT)
				dataY[j] = performFourierACF(imp);
	
			//AUTOCORRELATION WITHOUT FFT/FHT
			else
				imp.setRoi(oroi); //Set the ROI on the ImagePlus
				mean = imp.getStatistics().mean; //Calculate the mean of intensity in the ROI
				dataY[j] = getRadialProfile(imp.getProcessor(), xmin, xmax, ymin, ymax, X0, Y0, mean, useFFT);
		}

		// GENERATE THE DATAX ARRAY REGARDING THE SPATIAL CALIBRATION OF THE PICTURE
		if (useCalibration)
			{
				if(cal.pixelWidth != cal.pixelHeight)
				{
					cal.pixelWidth = ( cal.pixelWidth + cal.pixelHeight ) / 2;
					IJ.showMessage("WARNING", "Pixel Width is different from pixel\n" +
					"Height in spatial calibration!\n" +
					"Average pixel length used instead.");
				}
			
				for (i = 0; i < nBins; i++)
				{
					dataX[i]    = (float) (cal.pixelWidth * mR * ((double)(i + 1) / nBins));
				}

				unitName = cal.getUnits();
			}
			else
			{
				for (i = 0; i < nBins; i++)
				{
					dataX[i]    = (float) (mR * ((double) (i + 1) / nBins));
				}

				unitName = "pixels";
			}

		// DISPLAY THE MEAN STACK IF REQUIRED
		if (makeStackmean)
		{
			// Calculate the mean of the ACF on the stack
			float [][] MdataY;
			MdataY = new float[1][nBins];
			
			for (j = 0; j != imp.getStackSize(); j++)
			{
				for (i = 0; i < nBins; i++)
				{
					MdataY[0][i] = MdataY[0][i] + dataY[j][i];
				}
			}
			for (i = 0; i < nBins; i++)
			{
				MdataY[0][i] = MdataY[0][i] / imp.getStackSize();
			}

			plot = new MultyPlotExt("Mean AutoCorrelation on "+getImageTitle()+"", "Radius ["+unitName+"]", "AutoCorrelation",  dataX, MdataY[0]);
			headings[0] = "Radius ["+unitName+"]\t";

			headings[1] = "AutoCorrelation";
	        	MultyPlotWindowExt wnd = plot.show();
			wnd.setLineHeadings(headings, false);
		}

		// DISPLAY ALL THE CURVES
		else
		{
			minY = dataY[0][0];
			maxY = dataY[0][0];
			for (j = 0; j != imp.getStackSize(); j++)
			{
				extrema = Tools.getMinMax(dataY[j]);
				if (extrema[0] < minY)
					minY = (float) extrema[0];
				if (extrema[1] > maxY)
					maxY = (float) extrema[1];
			}

			plot.setLimits(dataX[0], dataX[nBins - 1], minY, maxY);

			for (j = 1; j != imp.getStackSize(); j++)
			{
				plot.setColor(new Color(colorGeneration(j,1) * 0xff, colorGeneration(j,1) * 0xff, colorGeneration(j,1) * 0xff));
				plot.addPoints(dataX, dataY[j], 2);
			}
			plot.setColor(new Color(0, 0, 0));		// This line is added so that dataY[0] which is actually drawn at last (for I don't know which reason - maybe a bug of the MultyPlotExt class) is drawn in black color (as expected from the code) and not with the last defined color of the previous loop
			MultyPlotWindowExt wnd = plot.show();
			wnd.setLineHeadings(headings, false);
//			wnd.setPrecision(3,3);
		}
	}

	// CALCULATE THE TIME ACF
	public void AutocorrTime()
	{
		int nPixels;
		nPixels = (int) ( 4 * mR * mR );
		float [][] scanDataY;
		float [][] dispDataY;
		float [] scanDataX;
		float [] testX;
		int [][] coodXY;
		int [][] finCoodXY;
		float minY, maxY;
		double[] extrema;
		int Nsize = imp.getStackSize();
		dataX  = new float[Nsize / 2];
		dataY  = new float[1][Nsize / 2];
		scanDataY = new float[nPixels][Nsize];
		scanDataX = new float[nPixels];
		dispDataY = new float[nPixels][Nsize];
		testX = new float[Nsize];
		coodXY = new int[nPixels][2];
		finCoodXY = new int[nPixels][2];
		String[] headings = new String[2];
		String[] headingsInt = new String[2];
		int i, j, k;
		int thisBin;
		double a, b, c, d;
		double R;
		double xmin = X0 - mR, xmax = X0 + mR, ymin = Y0 - mR, ymax = Y0 + mR;
		double xrange = xmax - xmin, yrange = ymax - ymin;
		double mean;
		float iMean;
		boolean pixelIntensity = false;
		Calibration cal = imp.getCalibration();

		String unitName;
		
		if (debugMode)
			pixelIntensity = true;
		
		if (cal == null || cal.frameInterval == 0)
			useTimeCalibration = false;
			
		OvalRoi oroi = new OvalRoi(xmin, ymin, 2 * mR, 2 * mR); //Set a new ROI with the same size and shape that the one set by the user
		Roi croi = new Roi(xmin, ymin, 2 * mR, 2 * mR); //Set a new ROI with the same size and shape that the one set by the user
	
		for (j = 0; j < Nsize; j++)
		{	
			imp.setSlice(j + 1);
			if (roiShape == "Circle")
				imp.setRoi(oroi);
			else
				imp.setRoi(croi);
			mean = imp.getStatistics().mean;
			ImageProcessor tempID = imp.getProcessor();
			FloatProcessor psID = new FloatProcessor(tempID.getWidth(), tempID.getHeight());
			psID = tempID.convertToFloatProcessor();
			
			if( pixelIntensity == true )
			{
				psID.add(- mean);
			}
			
			//SCAN THE ROI AND GET EVERY PIXEL VALUE			
			for (b = 0; b < yrange; b++)
			{
				for (a = 0; a < xrange; a++)
				{
					c = a + xmin;
					d = b + ymin;
					R = Math.sqrt((c - X0) * (c - X0) + (d - Y0) * (d - Y0));
					if( R <= mR || roiShape == "Square")
					{
						thisBin = (int) ( a + b * yrange );
						scanDataX   [thisBin] = scanDataX   [thisBin] + 1;
						scanDataY[thisBin][j] = psID.getPixelValue((int) c, (int) d);
						coodXY[thisBin][0] = (int) c;
						coodXY[thisBin][1] = (int) d;
					}
				}
			}
		}

//--------------------------------------------------------------
//		DEBUG - DISPLAY THE PIXEL INTENSITIES OVER TIME
		if (pixelIntensity)
		{
			k = 0;
			
			for (i = 0; i < nPixels; i++)
			{
				if (scanDataX[i] != 0)
				{
					dispDataY[k] = scanDataY[i];
					finCoodXY[k] = coodXY[i];
					k = k + 1;
				}
			}
			
			headingsInt = new String[k];
		
			minY = dispDataY[0][0];
			maxY = dispDataY[0][0];
			for (i = 0; i < k; i++)
			{
				extrema = Tools.getMinMax(scanDataY[i]);
				if (extrema[0] < minY)
					minY = (float) extrema[0];
				if (extrema[1] > maxY)
					maxY = (float) extrema[1];
			}
			
			for (i = 0; i < Nsize; i++)
			{
				testX[i] = i + 1;
			}
			
			if (useTimeCalibration)
			{
				for (i = 0; i < Nsize; i++)
					testX[i] = (float) (cal.frameInterval * (double) i);
				plotInt = new MultyPlotExt("Pixel Intensities on "+getImageTitle()+"", "Time ["+cal.getTimeUnit()+"]", "Intensity",  testX, dispDataY[0]);
				headingsInt[0] = "Time ["+cal.getTimeUnit()+"]";
			}
			else
			{
				plotInt = new MultyPlotExt("Pixel Intensities on "+getImageTitle()+"", "Time [picture]", "Intensity",  testX, dispDataY[0]);
				headingsInt[0] = "Time [picture]";
			}
			plotInt.setLimits(testX[0], testX[Nsize - 1], minY, maxY);
			
			for (j = 1; j < k; j++)
			{
				headingsInt[j] =  "("+String.valueOf(finCoodXY[j - 1][0])+";"+String.valueOf(finCoodXY[j - 1][1])+")";
				plotInt.setColor(new Color(colorGeneration(j,1) * 0xff, colorGeneration(j,2) * 0xff, colorGeneration(j,3) * 0xff));
				plotInt.addPoints(testX, dispDataY[j], 2);
			}
			plotInt.setColor(new Color(0, 0, 0));		// This line is added so that dataY[0] which is actually drawn at last (for I don't know which reason - maybe a bug of the MultyPlotExt class) is drawn in black color (as expected from the code) and not with the last defined color of the previous loop
			MultyPlotWindowExt wndInt = plotInt.show();
			wndInt.setLineHeadings(headingsInt, false);
		}
		//--------------------------------------------------------------

		
		//CALCULATE THE AUTOCORRELATION FUNCTION
		for (a = 0; a < xrange; a++)
		{
			for (b = 0; b < yrange; b++)
			{
				thisBin = (int) (b + a * xrange);
				if( scanDataX[thisBin] != 0 )
				{
					iMean = 0;
					for (j = 0; j < Nsize; j++)
					{
						iMean = iMean + scanDataY[thisBin][j];
					}
					iMean = iMean / Nsize;
					for (i = 0; i < Nsize / 2; i++)
					{
						for (j = 0; j < Nsize - i; j++)
						{
							dataY[0][i] = dataY[0][i] + (scanDataY[thisBin][j] - iMean) * (scanDataY[thisBin][j + i] - iMean);
							dataX[i] = dataX[i] + 1;
						}
					}
				}
			}
		}
			
		for (i = 0; i < Nsize / 2; i++)
		{
			dataY[0][i] = dataY[0][i] / dataX[i];
			dataX[i] = i;
		}
			
		// Normalize by the value at time origin
		float norm = dataY[0][0];
		for (i = 0; i < Nsize / 2; i++)
		{
			dataY[0][i] = dataY[0][i] / norm;
		}

		// Apply the time calibration if possible
		if (useTimeCalibration)
		{
			for (i = 0; i < Nsize / 2; i++)
				dataX[i] = (float) (cal.frameInterval * (double) i);
			unitName = cal.getTimeUnit();
		}
		else
			unitName = "picture";

		// Display the resulting graph
		plot = new MultyPlotExt("Pixel AutoCorrelation on "+getImageTitle()+"", "Time ["+ unitName +"]", "AutoCorrelation",  dataX, dataY[0]);
		headings[0] = "Time ["+ unitName +"]\t";
		headings[1] = "AutoCorrelation";
		MultyPlotWindowExt wnd = plot.show();
		wnd.setLineHeadings(headings, false);
	}
	
	// CALCULATE THE TIME ACF OVER A MEAN AREA
	public void AutocorrTimeArea()
	{
		int nScan;
		nScan = (int) mR;
		float [][] scanDataY;
		float [][] dispDataY;
		float [] scanDataX;
		float [] testX;
		float minY, maxY;
		double[] extrema;
		int Nsize = imp.getStackSize();
		dataX  = new float[Nsize / 2];
		dataY  = new float[nScan][Nsize / 2];
		scanDataY = new float[nScan][Nsize];
		scanDataX = new float[Nsize];
		testX = new float[Nsize];
		String[] headings = new String[nScan + 1];
		int i, j, k;
		int thisBin;
		int rScanned;
		double a, b, c, d;
		double R;
		double xmin = X0 - mR, xmax = X0 + mR, ymin = Y0 - mR, ymax = Y0 + mR;
		double xrange = xmax - xmin, yrange = ymax - ymin;
		float iMean;
		float norm;
		boolean pixelIntensity = false;
		String unitName;
		
		if (debugMode)
			pixelIntensity = true;
		
		Calibration cal = imp.getCalibration();
		
		if (cal == null || cal.frameInterval == 0)
			useTimeCalibration = false;
			
		if (cal == null || cal.getUnit() == "pixel")
			useCalibration = false;
		if (useCalibration)
			{
				if(cal.pixelWidth != cal.pixelHeight)
				{
					cal.pixelWidth = ( cal.pixelWidth + cal.pixelHeight ) / 2;
					IJ.showMessage("WARNING", "Pixel Width is different from pixel\n" +
					"Height in spatial calibration!\n" +
					"Average pixel length used instead.");
				}	
			}

		// Scan over the area size
		for (k = 0; k < nScan; k++)
		{	
			rScanned = k + 1;
			iMean = 0;
			
			if (useCalibration)
				headings[rScanned] = "R = " + IJ.d2s((rScanned * cal.pixelWidth), getDigits(rScanned * cal.pixelWidth, rScanned * cal.pixelWidth)) + " " + cal.getUnits() + "";
			else
				headings[rScanned] = "R = " + rScanned + " px";
		
			// Get the mean intensity value of the given area
			OvalRoi oroi = new OvalRoi(X0 - rScanned, Y0 - rScanned, 2 * rScanned, 2 * rScanned); //Set a new ROI with the same size and shape that the one set by the user
			Roi croi = new Roi(X0 - rScanned, Y0 - rScanned, 2 * rScanned, 2 * rScanned); //Set a new ROI with the same size and shape that the one set by the user
			
			// Scan over the time
			for (j = 0; j < Nsize; j++)
			{
				imp.setSlice(j+1);
				if(roiShape == "Circle")
					imp.setRoi(oroi);
				else
					imp.setRoi(croi);
				scanDataY[k][j] = (float) imp.getStatistics().mean;
				iMean = iMean + scanDataY[k][j];
			}
			
			iMean = iMean / Nsize;
			
			// Calculate the time ACF for all the frames
			for (i = 0; i < Nsize / 2; i++)
			{
				dataX[i] = 0;
				dataY[k][i] = 0;
			
				for (j = 0; j < Nsize - i; j++)
				{
					dataY[k][i] = dataY[k][i] + (scanDataY[k][j] - iMean) * (scanDataY[k][j + i] - iMean);
					dataX[i] = dataX[i] + 1;
				}
				
				dataY[k][i] = dataY[k][i] / dataX[i];
			}
			
			norm = (float) dataY[k][0];
			
			// Normalize by the value at time origin
			for (i = 0; i < Nsize/2; i++)
			{
				dataY[k][i] = dataY[k][i] / norm;
			}
			
		}
	
		//------------------------------------------------------
		//DEBUG MODE - DISPLAY THE PIXEL INTENSITIES OVER TIME
		if (pixelIntensity)
		{
		
			minY = scanDataY[0][0];
			maxY = scanDataY[0][0];
		
			for (j = 0; j < nScan; j++)
			{
				extrema = Tools.getMinMax(scanDataY[j]);
				if (extrema[0] < minY)
					minY = (float) extrema[0];
				if (extrema[1] > maxY)
					maxY = (float) extrema[1];
			
			}
			
			if (useTimeCalibration)
			{
				for (j = 0; j < Nsize; j++)
				{
					scanDataX[j] = (float) (cal.frameInterval * (double) j);
				}
		
				plot = new MultyPlotExt("Area Intensities on "+getImageTitle()+"","Time ["+cal.getTimeUnit()+"]", "Area Average Intensity", scanDataX, scanDataY[0]);
				headings[0] = "Time ["+cal.getTimeUnit()+"]";
			}
			else
			{
				for (j = 0; j < Nsize; j++)
				{
					scanDataX[j] = j + 1;
				}
		
				plot = new MultyPlotExt("Area Intensities on "+getImageTitle()+"","Time [picture]", "Area Average Intensity", scanDataX, scanDataY[0]);
				headings[0] = "Time [picture]";
			}
			
			plot.setLimits(scanDataX[0],scanDataX[Nsize - 1], minY, maxY);

			for (j = 1; j < nScan; j++)
			{
				plot.setColor(new Color(colorGeneration(j,1) * 0xff, colorGeneration(j,2) * 0xff, colorGeneration(j,3) * 0xff));
				plot.addPoints(scanDataX, scanDataY[j],2);
			}
		
			plot.setColor(new Color(0,0,0));
		
			MultyPlotWindowExt wnd = plot.show();
			wnd.setLineHeadings(headings, false);
		}
		//------------------------------------------------------

		// Get the extrema for the display
		minY = dataY[0][0];
		maxY = dataY[0][0];
		
		for (j = 0; j < nScan; j++)
		{
			extrema = Tools.getMinMax(dataY[j]);
			if (extrema[0] < minY)
				minY = (float) extrema[0];
			if (extrema[1] > maxY)
				maxY = (float) extrema[1];
		}
		
		// Apply the required time calibration
		if (useTimeCalibration)
		{
			for (j = 0; j < Nsize / 2; j++)
			{
				dataX[j] = (float) (cal.frameInterval * (double) j);
			}

			unitName = cal.getTimeUnit();
		}
		else
		{
			for (j = 0; j < Nsize / 2; j++)
			{
				dataX[j] = j + 1;
			}
		
			unitName = "picture";
		}
		
		// Display the graph
		plot = new MultyPlotExt("Area AutoCorrelation on "+getImageTitle()+"","Time ["+unitName+"]", "AutoCorrelation", dataX, dataY[0]);
		headings[0] = "Time ["+unitName+"]";
		plot.setLimits(dataX[0],dataX[(Nsize / 2) - 1], minY, maxY);

		for (j = 1; j < nScan; j++)
		{
			plot.setColor(new Color(colorGeneration(j,1) * 0xff, colorGeneration(j,2) * 0xff, colorGeneration(j,3) * 0xff));
			plot.addPoints(dataX, dataY[j],2);
		}
		
		plot.setColor(new Color(0,0,0));
		MultyPlotWindowExt wnd = plot.show();
		wnd.setLineHeadings(headings, false);
		
	}

	
	// CALCULATE THE ACF OVER WAVELENGTH
	public void WavelengthACF()
	{
		//Initialize all the variables
		nBins = (int) (3*mR/4);
		int nPixels = (int) (4 * mR * mR);
		float [][] scanDataY; //Definition of all arrays
		float [] scanDataX;
		float [][] scanDataDispY;
		float [] scanDataDispX;
		float [] ampY;
		float [] ampX;
		int[][] coodXY;
		int[][] finCoodXY;
		dataX  = new float   [imp.getStackSize() / 2]; //Array for ACF values
		dataY  = new float[resolWav][imp.getStackSize() / 2];
		scanDataY = new float[nPixels][imp.getStackSize()]; //Temporary array for collection of pixel intensities
		scanDataX = new float[nPixels];
		scanDataDispY = new float[nPixels][imp.getStackSize()];
		scanDataDispX = new float[imp.getStackSize()];
		ampY = new float[resolWav]; //Array for Amplitude (norm) of every modes
		ampX = new float[resolWav];
		coodXY = new int[nPixels][2];
		finCoodXY = new int[nPixels][2];
		String[] headings = new String[resolWav + 1]; //Array for the headings (String)
		String[] headingsAmp = new String[2];
		String[] headingsInt = new String[2];
		int i, j, k, m;
		int thisBin;
		int xOff, yOff;		
		double a, b, c, d;
		double R, Rscan;
		double xmin = X0 - mR, xmax = X0 + mR, ymin = Y0 - mR, ymax = Y0 + mR;
		double xminscan, xmaxscan, yminscan, ymaxscan;
		double minFFTsize = 2 * mR, FFTsize = 2;
		double mean;
		float iMean;
		float minY, maxY;
		double[] extrema;
		
		//DEBUG INITIALIZATION
		boolean debugDisplay = false;
		boolean debugInt = false;
		boolean debugFilter = false;
		if(debugMode)
		{
			debugDisplay = true; //Only display the very first cropped picture in debug mode
			debugInt = true; //Only display the pixel intensities over time for first filter OR selected filter
			debugFilter = true;
		}
		
		Calibration cal = imp.getCalibration();  //Get the calibration of the stack and check if it exists
		if (cal == null || cal.getUnit() == "pixel")
			useCalibration = false;
		if (cal == null || cal.frameInterval == 0)
			useTimeCalibration = false;
		
		int powCount = 1;
		while (FFTsize < minFFTsize)
		{
			FFTsize = FFTsize * 2; //Set the size of the processed picture to 2^n * 2^n, where 2^n is bigger than the ROI size
			powCount = powCount + 1;
		}
		
		if(typeStep == "Power of 2")
			resolWav = powCount + 1;

		ImageStack filtStack = new ImageStack((int) FFTsize,(int) FFTsize);
		
		for (j = 0; j < resolWav; j++) //Loop on all the filters to perform
		{
			ImageStack newStack = new ImageStack(1,1); //Create the new stack, for diplaying filters
			
			if (j == (displayFiltNumber - 1) && displayFilter) //Check if the current processed filter is the one to display
				newStack = new ImageStack((int)FFTsize,(int)FFTsize);
		
			for (k = 0; k != imp.getStackSize(); k++) //Loop on all the pictures of the stack
			{
				imp.setSlice(k + 1); //Move to the next slice of the stack
				ImageProcessor epsID = imp.getProcessor(); //Get the ImageProcessor of the ccurrect slice of the stack
		
				//CREATION OF THE CROPPED IMAGE FOR FFT
				ImagePlus psImg = new ImagePlus("resized", epsID);
				psImg = cropROI(epsID);
				
				// DEBUG - DISPLAY PICTURE AFTER CROPPING
				if(debugDisplay)
				{
					psImg.show();
					debugDisplay = false;
				}
				
				FHT fht1; //Create a new FHT
		
				//LOOP FOR WAVELENGTH SCAN
			
				//FFT
				fht1 = new FHT(psImg.getProcessor()); //Copy the processor of the picture in the FHT
				fht1.transform(); //Perform the Fast Fourier Transform of the picture
				
				if (smoothBandpass)
				{
					double filterSmall;
					double filterLarge;
		
					//BANDPASS FILTER
					if (j == 0) //Define the new values for the Min and Max wavelength of the current filter
						filterSmall = (double) 0; //Set to 0 for the first filter
					else
					{
						if (typeStep == "Power of 2")
							filterSmall = (double) Math.pow(2,(j-1)) / (FFTsize/2);
						else if (typeStep == "Linear")
							filterSmall = (double) j / (2*resolWav);
						else
							filterSmall = (double) 2 / (resolWav + 1 - j);
					}
					if (j == resolWav - 1)
						filterLarge = 2.0;
					else
					{
						if (typeStep == "Power of 2")
							filterLarge = (double) Math.pow(2,j) / (FFTsize/2);
						else if (typeStep == "Linear")
							filterLarge = (double) (j+1) / (2*resolWav);
						else
							filterLarge = (double) 2 / (resolWav - j);
					}
					int maxN = (int) FFTsize;

					float[] fht2 = (float[])fht1.getPixels(); //Get references of the pixel of fht1
					float[] filter = new float[maxN*maxN];
					for (i = 0; i < maxN*maxN; i++)
						filter[i] = 1f;
				
					int row; //Initialize variables
					int backrow;
					float rowFactLarge;
					float rowFactSmall;
					int col;
					int backcol;
					float factor;
					float colFactLarge;
					float colFactSmall;
					double scaleStripes = 0.95 * 0.95;
					float factStripes;
					double scaleLarge = filterLarge * filterLarge;
					double scaleSmall = filterSmall * filterSmall;
		
					//CALCULATE FACTOR IN EXPONENT OF GAUSSIAN FROM FILTERLARGE / FILTERSMALL
					for(i = 1; i < maxN/2; i++)
					{
						row = i * maxN;
						backrow = (maxN - i) * maxN;
						rowFactLarge = (float) Math.exp(-(i*i)*scaleLarge);
						rowFactSmall = (float) Math.exp(-(i*i)*scaleSmall);
			
						for(col = 1; col < maxN/2; col++)
						{
							backcol = maxN - col;
							colFactLarge = (float) Math.exp(- (col*col) * scaleLarge);
							colFactSmall = (float) Math.exp(- (col*col) * scaleSmall);
							factor = (1 - rowFactLarge*colFactLarge) * rowFactSmall*colFactSmall;
					
							fht2[col+row] *= factor;
							fht2[col+backrow] *= factor;
							fht2[backcol+row] *= factor;
							fht2[backcol+backrow] *= factor;
							filter[col+row] *= factor;
							filter[col+backrow] *= factor;
							filter[backcol+row] *= factor;
							filter[backcol+backrow] *= factor;
						}
					}
		
					//PROCESS MEETING POINTS
					int rowmid = maxN * (maxN / 2);
					rowFactLarge = (float) Math.exp(- (maxN / 2) * (maxN / 2) * scaleLarge);
					rowFactSmall = (float) Math.exp(- (maxN / 2) * (maxN / 2) * scaleSmall);
					factStripes = (float) Math.exp(- (maxN / 2) * (maxN / 2) * scaleStripes);
		
					fht2[maxN/2] *= (1 - rowFactLarge) * rowFactSmall;
					fht2[rowmid] *= (1 - rowFactLarge) * rowFactSmall;
					fht2[maxN/2 + rowmid] *= (1 - rowFactLarge * rowFactLarge) * rowFactSmall * rowFactSmall;
					filter[maxN/2] *= (1 - rowFactLarge) * rowFactSmall;
					filter[rowmid] *= (1 - rowFactLarge) * rowFactSmall;
					filter[maxN/2 + rowmid] *= (1 - rowFactLarge * rowFactLarge) * rowFactSmall * rowFactSmall;

		
					//LOOP ALONG ROW 0 AND MAXN/2
					for (col = 1; col < maxN/2; col++)
					{	
						backcol = maxN - col;
						colFactLarge = (float) Math.exp(-(col*col)*scaleLarge);
						colFactSmall = (float) Math.exp(-(col*col)*scaleSmall);
						
						fht2[col] *= (1 - colFactLarge) * colFactSmall;
						fht2[backcol] *= (1 - colFactLarge) * colFactSmall;
						fht2[col+rowmid] *= (1 - colFactLarge * rowFactLarge) * colFactSmall * rowFactSmall;
						fht2[backcol+rowmid] *= (1 - colFactLarge * rowFactLarge) * colFactSmall * rowFactSmall;
						filter[col] *= (1 - colFactLarge) * colFactSmall;
						filter[backcol] *= (1 - colFactLarge) * colFactSmall;
						filter[col+rowmid] *= (1 - colFactLarge * rowFactLarge) * colFactSmall * rowFactSmall;
						filter[backcol+rowmid] *= (1 - colFactLarge * rowFactLarge) * colFactSmall * rowFactSmall;
					
					}		
		
					//LOOP ALONG COLUMN 0 AND MAXN/2
					colFactLarge = (float) Math.exp(-((maxN/2)*(maxN/2))*scaleLarge);
					colFactSmall = (float) Math.exp(-((maxN/2)*(maxN/2))*scaleSmall);
					for (i = 1; i < maxN/2; i++)
					{
						row = i * maxN;
						backrow = (maxN -i) * maxN;
						rowFactLarge = (float) Math.exp(-(i*i)*scaleLarge);
						rowFactSmall = (float) Math.exp(-(i*i)*scaleSmall);
			
						fht2[row] *= (1 - rowFactLarge) * rowFactSmall;
						fht2[backrow] *= (1 - rowFactLarge) * rowFactSmall;
						fht2[row+maxN/2] *= (1 - colFactLarge * rowFactLarge) * colFactSmall * rowFactSmall;
						fht2[backrow+maxN/2] *= (1 - colFactLarge * rowFactLarge) * colFactSmall * rowFactSmall;
						filter[row] *= (1 - rowFactLarge) * rowFactSmall;
						filter[backrow] *= (1 - rowFactLarge) * rowFactSmall;
						filter[row+maxN/2] *= (1 - colFactLarge * rowFactLarge) * colFactSmall * rowFactSmall;
						filter[backrow+maxN/2] *= (1 - colFactLarge * rowFactLarge) * colFactSmall * rowFactSmall;

					}
				
					if(debugFilter)
					{
						if(k == 0)
						{
							FHT f = new FHT(new FloatProcessor((int)FFTsize,(int) FFTsize, filter, null));
							f.swapQuadrants();
							filtStack.addSlice("Filter " + (j+1) + "", f);
						}
					}
				}
				else
				{
					double filterSmall;
					double filterLarge;
					float[] filter = new float[(int) (FFTsize*FFTsize)];
					for (i = 0; i < FFTsize*FFTsize; i++)
						filter[i] = 1f;
		
					//BANDPASS FILTER
					if (j == 0) //Define the new values for the Min and Max wavelength of the current filter
						filterSmall = (double) 0; //Set to 0 for the first filter
					else
					{
						if(typeStep == "Power of 2")
							filterSmall = (double) Math.pow(2,(j-1));
						else if (typeStep == "Linear")
							filterSmall = (double) (FFTsize/2) * (float) j / resolWav; //Linear form
						else
							filterSmall = (double) FFTsize * ((1f / (resolWav + 1f - j)) - (1f / (resolWav+1f))); //Inverse form
					}
					if (j == (resolWav - 1))
						filterLarge = (double) 2*FFTsize;
					else
					{
						if(typeStep == "Power of 2")
							filterLarge = (double) Math.pow(2,j);
						else if (typeStep == "Linear")
							filterLarge = (double) (FFTsize/2) * (float) (j + 1) / resolWav; //Linear form
						else
							filterLarge = (double) FFTsize * ((1f / (resolWav - j)) - (1f / (resolWav+1f))); //Inverse form
					}

					fht1.swapQuadrants();
					
					float[] fht2 = (float[])fht1.getPixels();
					
					for (a = 0; a < FFTsize; a++)
					{
						for (b = 0; b < FFTsize; b++)
						{
							R = Math.sqrt((a - FFTsize/2)*(a - FFTsize/2) + (b - FFTsize/2)*(b - FFTsize/2));
				//			R = Math.sqrt(a*a + b*b);
							
							if(typeStep == "Power of 2")
							{
								if(j != 0 && ((R < filterSmall) || (R > filterLarge)))
								{
									fht2[(int) ((a*FFTsize) + b)] = 0f;
									filter[(int) ((a*FFTsize) + b)] = 0f;
								}
								else if (j != 0 && a == FFTsize/2 && b == FFTsize/2)
								{
									fht2[(int) ((a*FFTsize) + b)] = 0f;
									filter[(int) ((a*FFTsize) + b)] = 0f;
								}
								else if (j == 0 && (a*FFTsize+b != (FFTsize*FFTsize/2 + FFTsize/2)))
								{
									fht2[(int) ((a*FFTsize) + b)] = 0f;
									filter[(int) ((a*FFTsize) + b)] = 0f;
								}
							}
							else
							{
								if((R < filterSmall) || (R > filterLarge))
								{
									fht2[(int) ((a*FFTsize) + b)] = 0f;
									filter[(int) ((a*FFTsize) + b)] = 0f;
								}								
							}
						}
					}
					
					fht1.swapQuadrants();
					
					if(debugFilter)
					{
						if(k == 0)
						{
							FHT f = new FHT(new FloatProcessor((int)FFTsize,(int) FFTsize, filter, null));
							filtStack.addSlice("Filter " + (j+1) + "", f);
						}
					}	
				}
			
				fht1.inverseTransform(); //Make the Inverse FFT of the picture
				
				if (j == (displayFiltNumber - 1) && displayFilter) //Add the picture to the stack if it is the filter to display
					newStack.addSlice("Picture n" + (k + 1) + "", fht1);
				
				//SCAN THE ROI AND GET EVERY PIXEL VALUE
				for (b = 0; b < 2*mR; b++)
				{
					for (a = 0; a < 2*mR; a++)
					{
						c = a + (FFTsize / 2) - mR;
						d = b + (FFTsize / 2) - mR;
						R = Math.sqrt((c - FFTsize/2)*(c - FFTsize/2) + (d - FFTsize/2)*(d - FFTsize/2));
						if (R <= mR || roiShape == "Square") //Check if the pixel is in the ROI
						{
							thisBin = (int) (a + b * 2*mR);
							scanDataX[thisBin] = 1; //Define the pixel thisBin as scanned
							scanDataY[thisBin][k] = fht1.getPixelValue((int)c, (int)d); //Save the value of the pixel in the scanData filter
							coodXY[thisBin][0] = (int) (a + xmin); //Get coordinates for DEBUG mode
							coodXY[thisBin][1] = (int) (b + ymin);
						}
						else
						{
							thisBin = (int) (a + b * 2*mR);
							scanDataX[thisBin] = 0; //Define the pixel thisBin as unscanned
						}
					}
				}				
			}
			
			if (j == (displayFiltNumber - 1) && displayFilter) //Display the stack of the filter when the scan over all the pictures of the stack is done
			{
				ImagePlus newDisplay = new ImagePlus("Filtered pictures with filter " + (j + 1) + "", newStack);
				newDisplay.show();
				IJ.run(newDisplay, "Enhance Contrast", "saturated=0.35");
			}
			

			//----------------------------------------------------------------------
			//DEBUG - DISPLAY PIXEL INTENSITIES OVER TIME
			if (debugInt)
			{
				if (displayFilter) //Check if the display filter command is on
				{
					if(j == (displayFiltNumber - 1)) //If yes, display the pixel intensities of the selected filter
					{
						m = 0;
						for (i = 0; i < nPixels; i++)
						{
							if(scanDataX[i] == 1)
							{
								scanDataDispY[m] = scanDataY[i];
								finCoodXY[m] = coodXY[i];
								m = m+1;
							}
						}
						headingsInt = new String[m];
						
						minY = scanDataDispY[0][0];
						maxY = scanDataDispY[0][0];
						
						for (i = 0; i < m; i++)
						{
							extrema = Tools.getMinMax(scanDataDispY[i]);
							if(extrema[0] < minY)
								minY = (float) extrema[0];
							if(extrema[1] > maxY)
								maxY = (float) extrema[1];
						}
						
						if (useTimeCalibration)
						{
							for (i = 0; i < imp.getStackSize(); i++)
								scanDataDispX[i] = (float) (cal.frameInterval * (double) i);
							plotInt = new MultyPlotExt("Pixel intensities on "+getImageTitle()+" for filter "+ (j+1) +" at (" + X0 + "," + Y0 + "), " + roiShape + " with size = " + mR + "", "Time ["+cal.getTimeUnit()+"]", "Pixel Intensities", scanDataDispX, scanDataDispY[0]);
							headingsInt[0] = "Time ["+cal.getTimeUnit()+"]";
						}
						else
						{
							for (i = 0; i < imp.getStackSize(); i++)
								scanDataDispX[i] = (float) i;
							plotInt = new MultyPlotExt("Pixel intensities on "+getImageTitle()+" for filter "+ (j+1) +" at (" + X0 + "," + Y0 + "), " + roiShape + " with size = " + mR + "", "Time [picture]", "Pixel Intensities", scanDataDispX, scanDataDispY[0]);
							headingsInt[0] = "Time [picture]";
						}
						
						plotInt.setLimits(scanDataDispX[0], scanDataDispX[imp.getStackSize()-1], minY, maxY);
						
						for (i = 1; i < m; i++)
						{
							headingsInt[i] = "("+String.valueOf(finCoodXY[i-1][0])+";"+String.valueOf(finCoodXY[i-1][1])+")";
							plotInt.setColor(new Color(colorGeneration(i,1)*0xff, colorGeneration(i,2)*0xff, colorGeneration(i,3)*0xff));
							plotInt.addPoints(scanDataDispX, scanDataDispY[i], 2);
						}
						plotInt.setColor(new Color(0,0,0));
						MultyPlotWindowExt wndInt = plotInt.show();
						wndInt.setLineHeadings(headingsInt, false);
					}
				}
				else //If it's not on, display the pixel intensities of the first filter
				{
					m = 0;
					for (i = 0; i < nPixels; i++)
					{
						if(scanDataX[i] != 0)
						{
							scanDataDispY[m] = scanDataY[i];
							finCoodXY[m] = coodXY[i];
							m = m+1;
						}
					}
					headingsInt = new String[m];
					
					minY = scanDataDispY[0][0];
					maxY = scanDataDispY[0][0];
						
					for (i = 0; i < m; i++)
					{
						extrema = Tools.getMinMax(scanDataDispY[i]);
						if(extrema[0] < minY)
							minY = (float) extrema[0];
						if(extrema[1] > maxY)
							maxY = (float) extrema[1];
					}
					
					if (useTimeCalibration)
					{
						for (i = 0; i < imp.getStackSize(); i++)
							scanDataDispX[i] = (float) (cal.frameInterval * (double) i);
						plotInt = new MultyPlotExt("Pixel intensities on "+getImageTitle()+" for filter "+ (j+1) +" at (" + X0 + "," + Y0 + "), " + roiShape + " with size = " + mR + "", "Time ["+cal.getTimeUnit()+"]", "Pixel Intensities", scanDataDispX, scanDataDispY[0]);
						headingsInt[0] = "Time ["+cal.getTimeUnit()+"]";
					}
					else
					{
						for (i = 0; i < imp.getStackSize(); i++)
							scanDataDispX[i] = (float) i;
						plotInt = new MultyPlotExt("Pixel intensities on "+getImageTitle()+" for filter "+ (j+1) +" at (" + X0 + "," + Y0 + "), " + roiShape + " with size = " + mR + "", "Time [picture]", "Pixel Intensities", scanDataDispX, scanDataDispY[0]);
						headingsInt[0] = "Time [picture]";
					}
						
					for (i = 1; i < m; i++)
					{
						headingsInt[i] = "("+String.valueOf(finCoodXY[i-1][0])+";"+String.valueOf(finCoodXY[i-1][1])+")";
						plotInt.setColor(new Color(colorGeneration(i,1)*0xff, colorGeneration(i,2)*0xff, colorGeneration(i,3)*0xff));
						plotInt.addPoints(scanDataDispX, scanDataDispY[i], 2);
					}
					plotInt.setColor(new Color(0,0,0));
					plotInt.setLimits(scanDataDispX[0], scanDataDispX[imp.getStackSize()-1], minY, maxY);
					MultyPlotWindowExt wndInt = plotInt.show();
					wndInt.setLineHeadings(headingsInt, false);
				}
				debugInt = false;
			}
			//DEBUG - END OF PIXEL INTENSITIES DISPLAY
			//----------------------------------------------------------------------

				
			//CALCULATE THE AUTOCORRELATION FUNCTION FOR THE J FILTER
				
			for (a = 0; a < 2*mR; a++)
			{
				for (b = 0; b < 2*mR; b++)
				{
					thisBin = (int) (b + a * 2*mR);
					
					if(scanDataX[thisBin] != 0)
					{
						//CALCULATE THE MEAN INTENSITY VALUE OF THIS PIXEL
						iMean = 0;
						for (k = 0; k < imp.getStackSize(); k++)
						{
							iMean = iMean + scanDataY[thisBin][k];
						}
						iMean = iMean / imp.getStackSize();
						
						//CALCULATE Ipixel(j)*Ipixel(j+i)
						for (i = 0; i < imp.getStackSize() / 2; i++)
						{
							for (k = 0; k < imp.getStackSize() - i; k++)
							{
								dataY[j][i] = dataY[j][i] + (scanDataY[thisBin][k] - iMean) * (scanDataY[thisBin][k+i] - iMean);
								dataX[i] = dataX[i] + 1;
							}
						}
					}
				}
			}
				
			for (i = 0; i < imp.getStackSize() / 2; i++)
			{
				dataY[j][i] = dataY[j][i] / dataX[i];
				dataX[i] = 0;
			}
			
			//NORMALIZE BY THE FIRST VALUE (t = 0)
			float norm = dataY[j][0];
			ampY[j] = norm;
			
			for (i = 0; i < imp.getStackSize() / 2; i++)
			{
				dataY[j][i] = dataY[j][i] / norm;
			}
			
		IJ.showProgress((int) (j+1), (int) resolWav);
			
		}
		
		if(debugFilter)
		{
			ImagePlus filtDisplay = new ImagePlus("Filters", filtStack);
			filtDisplay.show();
		}

		//SET THE X-AXIS OF THE GRAPH
		for (i = 0; i < imp.getStackSize() / 2; i++)
		{
			dataX[i] = i;
		}
		
		//SET THE LIMITS OF THE PLOT
		minY = dataY[0][0];
		maxY = dataY[0][0];
		for (i = 0; i < resolWav; i++)
		{
			extrema = Tools.getMinMax(dataY[i]);
			if(extrema[0] < minY)
				minY = (float) extrema[0];
			if(extrema[1] > maxY)
				maxY = (float) extrema[1];
		}
		
		//PLOT THE RESULTS
		if (useTimeCalibration)
		{
			for (i = 0; i < imp.getStackSize() / 2; i++)
				dataX[i] = (float) (cal.frameInterval * (double) i);
			plot = new MultyPlotExt("Wavelength AutoCorrelation on "+getImageTitle()+" at (" + X0 + "," + Y0 + "), " + roiShape + " with size = " + mR + "", "Time ["+cal.getTimeUnit()+"]", "AutoCorrelation", dataX, dataY[0]);
			headings[0] = "Time ["+cal.getTimeUnit()+"]";
		}
		else
		{
			plot = new MultyPlotExt("Wavelength AutoCorrelation on "+getImageTitle()+" at (" + X0 + "," + Y0 + "), " + roiShape + " with size = " + mR + "", "Time [picture]", "AutoCorrelation", dataX, dataY[0]);
			headings[0] = "Time [picture]";
		}
		
		plot.setLimits(dataX[0], dataX[(imp.getStackSize() / 2) - 1], minY, maxY);
		
		if (useCalibration)
		{
				
			if(cal.pixelWidth != cal.pixelHeight)
			{
				cal.pixelWidth = ( cal.pixelWidth + cal.pixelHeight ) / 2;
				IJ.showMessage("WARNING", "Pixel Width is different from pixel\n" +
				"Height in spatial calibration!\n" +
				"Average pixel length used instead.");
			}	
			headings[1] = ""+ IJ.d2s(((0.5)*mR*cal.pixelWidth/resolWav), getDigits((0.5)*mR*cal.pixelWidth/resolWav, (0.5)*mR*cal.pixelWidth/resolWav)) + " " + cal.getUnit() + "";
			ampX[0] = (float) ((0.5)*cal.pixelWidth*mR/resolWav);
		}
		else
		{
			headings[1] = ""+ IJ.d2s(((0.5)*mR/resolWav), getDigits((0.5)*mR/resolWav, (0.5)*mR/resolWav)) + " pixel";
			ampX[0] = (float) ((0.5)*mR/resolWav);
		}
		
		for (i = 1; i < resolWav; i++)
		{
			plot.setColor(new Color(colorGeneration(i,1) * 0xff, colorGeneration(i,2) * 0xff, colorGeneration(i,3) * 0xff));
			plot.addPoints(dataX, dataY[i],2);
			if (useCalibration)
			{
				headings[i+1] = ""+ IJ.d2s(((i + 0.5)*mR*cal.pixelWidth/resolWav), getDigits((i + 0.5)*mR*cal.pixelWidth/resolWav, (i + 0.5)*mR*cal.pixelWidth/resolWav)) + " " + cal.getUnit() + "";
				ampX[i] = (float) ((i + 0.5)*mR*cal.pixelWidth/resolWav);
			}
			else
			{
				headings[i+1] = ""+ IJ.d2s(((i + 0.5)*mR/resolWav), getDigits((i + 0.5)*mR/resolWav, (i + 0.5)*mR/resolWav)) + " pixels";
				ampX[i] = (float) ((i + 0.5)*mR/resolWav);
			}
		}		
		plot.setColor(new Color(0,0,0));		
		MultyPlotWindowExt wnd = plot.show();
		wnd.setLineHeadings(headings,false);
		
		if (displayAmplitude) //Display the amplitude if selected by the user
		{
			if(useCalibration)
			{
				plotAmp = new MultyPlotExtScN("Amplitude on " +getImageTitle()+" at (" + X0 + "," + Y0 + "), " + roiShape + " with size = " + mR + "", "Wavelength ["+ cal.getUnit() +"]", "Amplitude", ampX, ampY);
				headingsAmp[0] = "Wavelength ["+ cal.getUnit() +"]";
			}
			else
			{
				plotAmp = new MultyPlotExtScN("Amplitude on " +getImageTitle()+" at (" + X0 + "," + Y0 + "), " + roiShape + " with size = " + mR + "", "Wavelength [pixel]", "Amplitude", ampX, ampY);
				headingsAmp[0] = "Wavelength [pixel]";
			}
			
			double[] extremaAmp;
			float[] tempAmp = new float[resolWav - 1];
			for(i = 1; i < resolWav; i++)
			{
				tempAmp[i-1] = ampY[i];
			}
			
			minY = ampY[1];
			maxY = ampY[1];
			extremaAmp = Tools.getMinMax(tempAmp);
			minY = (float) extremaAmp[0];
			maxY = (float) extremaAmp[1];
			plotAmp.setLimits(ampX[0], ampX[resolWav - 1], minY, maxY);
			headingsAmp[1] = "Amplitude";
			MultyPlotWindowExtScN wndAmp = plotAmp.show();
			wndAmp.setLineHeadings(headingsAmp, false);
		}
	}

	// CALCULATE THE RADIAL PROFILE OF INTENSITY
	private float[] getRadialProfile(ImageProcessor scannedImageP, double xmin, double xmax, double ymin, double ymax, double xCenter, double yCenter, double mean, boolean processFFT)
	{
		nBins = (int) (3*mR/4);

		int thisBin;
		int i;

		double a, b, c, d;
		double R, Rscan;
		double xMinScan, xMaxScan, yMinScan, yMaxScan;

		float[] scannedDataX, scannedDataY;
		scannedDataX = new float [nBins];
		scannedDataY = new float [nBins];

		IJ.log("Scanning all the pixels of the image of interest");

		// SCAN ALL THE PIXELS
		for (a = xmin; a <= xmax; a++)
		{
			for (b = ymin; b <= ymax; b++)
			{
				R = Math.sqrt((a-xCenter)*(a-xCenter)+(b-yCenter)*(b-yCenter));

				// GET ALL THE PIXELS VALUES FOR ACF WITH FFT
				if (processFFT)
				{
					if( (a - xCenter)/R >= -1.0 && (a - xCenter)/R <= 1.0)
					{
						if( (yCenter - b)/R >= -1.0 && (yCenter - b)/R <= 1.0)
						{
							thisBin = (int) Math.floor((R/mR)* (double) nBins);
							if (thisBin ==0) thisBin = 1;
							thisBin = thisBin-1;
							//if (thisBin > nBins -1) thisBins = nBins -1; // Correction suggested by Kota Miura

							if (thisBin < nBins)
							{
								scannedDataX[thisBin] = scannedDataX[thisBin] + 1;
								scannedDataY[thisBin] = scannedDataY[thisBin] + scannedImageP.getPixelValue((int) a, (int) b);
							}
						}
					}
				}

				// SCAN ALL THE PIXELS A SECOND TIME FOR ACF WITHOUT FFT
				else
				{
					if (R <= mR)
					{
						xMinScan = a - mR;
						xMaxScan = a + mR;
						yMinScan = b - mR;
						yMaxScan = b + mR;

						for (c = xMinScan; c <= xMaxScan; c++)
						{
							for (d = yMinScan; d <= yMaxScan; d++)
							{
								Rscan = Math.sqrt((c-a)*(c-a)+(d-b)*(d-b));
								if ((R+Rscan) <= mR)
								{
									thisBin = (int) Math.floor((Rscan/mR)* (double) nBins);
									if (thisBin ==0) thisBin = 1;
									thisBin = thisBin-1;
									//if (thisBin > nBins -1) thisBins = nBins -1; // Correction suggested by Kota Miura

									if (thisBin < nBins)
									{
										scannedDataX[thisBin] = scannedDataX[thisBin] + 1;
										scannedDataY[thisBin] = scannedDataY[thisBin] + (scannedImageP.getPixelValue((int) a, (int) b) - (float) mean) * (scannedImageP.getPixelValue((int) a, (int) b) - (float) mean);
									}
								}
							}
						}
					}
				}
			}
		}

		IJ.log("Calculation of the mean values");

		// CALCULATE THE MEAN VALUES OF EVERY PIXELS
		for (i = 0; i < nBins; i++)
		{
			IJ.log("Scan... Radius: "+Integer.toString(i));
			scannedDataY[i] = scannedDataY[i] / scannedDataX[i];
		}

		IJ.log("Scan achieved. Normalisation of the intensities");

		if ( processFFT == false)
		{
			float norm = scannedDataY[0];
			for (i = 0; i < nBins; i++)
			{
				scannedDataY[i] = scannedDataY[i] / norm;
			}
		}

		IJ.log("Returning values");

		return scannedDataY;

	}

	// CALCULATE THE SPATIAL AUTOCORRELATION USING THE FFT
	private float[] performFourierACF(ImagePlus impToCheck)
	{
		nBins = (int) (3*mR)/4;

		double minFFTsize = 2* mR;
		double FFTsize = findFourrierSize(minFFTsize);
		double xmin, xmax, ymin, ymax;

		IJ.log("Prepare the picture for the FFT");

		float[] fourierDataY;
			
		ImageProcessor epsID = impToCheck.getProcessor();
			
		ImagePlus psImg = new ImagePlus("resized", epsID);
		psImg = cropROI(epsID);
		
		/*	
		if(debugDisplay)
		{
			psImg.show();
			debugDisplay = false;
		}
		*/
		
		FHT h1;
		ImageProcessor fht1;
		fht1  = (ImageProcessor)psImg.getProperty("FHT");
		if (fht1!=null)
			h1 = new FHT(fht1);
		else {
			ImageProcessor ip1 = psImg.getProcessor();
			h1 = new FHT(ip1);
		}
		if (fht1==null) {
			h1.transform();
		}
		FHT result1=null;
		result1 = h1.conjugateMultiply(h1);
		result1.inverseTransform();
		result1.swapQuadrants();
		result1.resetMinAndMax();
		Calibration cal1 = psImg.getCalibration();
		ImagePlus psImg2 = new ImagePlus("FFT", result1);
		psImg2.setCalibration(cal1);
		cal1.disableDensityCalibration();		
		ImageProcessor psFFT;
		psFFT = psImg2.getProcessor();		
		float norm = psFFT.getPixelValue((int) FFTsize/2, (int) FFTsize/2); //Normalisation of the ACF
		psFFT.multiply(1 / norm); 

		IJ.log("FFT achieved correctly");
			
		//RADIAL PROFILE ANGLE EXTENDED (Modded from Philippe Carl)
		xmin = (FFTsize / 2) - mR;
		xmax = (FFTsize / 2) + mR;
		ymin = (FFTsize / 2) - mR;
		ymax = (FFTsize / 2) + mR;

		fourierDataY = getRadialProfile(psFFT, xmin, xmax, ymin, ymax, (FFTsize / 2), (FFTsize / 2), 0, useFFT);

		return fourierDataY;
	}

	// CROP THE ROI FROM THE PICTURE FOR MEASUREMENT
	public ImagePlus cropROI(ImageProcessor cropProc)
	{
		int xOff, yOff;
		double minFFTsize = 2 * mR, FFTsize = 4;
		double xmin = X0 - mR, xmax = X0 + mR, ymin = Y0 - mR, ymax = Y0 + mR;
		double mean;
		
		while (FFTsize < minFFTsize) FFTsize = FFTsize * 2; //Set the size of the processed picture to 2^n * 2^n, where 2^n is bigger than the ROI size
		
		OvalRoi oroi = new OvalRoi(xmin, ymin, 2 * mR, 2 * mR); //Set a new ROI with the same size and shape that the one set by the user
		OvalRoi oroi2 = new OvalRoi((FFTsize / 2) - mR, (FFTsize / 2) - mR, 2 * mR, 2 * mR); //Set a new ROI similar to the first one, but with new coordinates
		Roi croi = new Roi(xmin, ymin, 2 * mR, 2 * mR); //Set the new ROIs but in rectangular shape
		Roi croi2 = new Roi((FFTsize / 2) - mR, (FFTsize / 2) - mR, 2 * mR, 2 * mR);
		
		ImagePlus psImg = new ImagePlus("resized", cropProc);
		
		if (roiShape == "Square")
		{
			imp.setRoi(croi);
			mean = imp.getStatistics().mean; //Calculate the mean of intensity in the ROI
			cropProc.setRoi(croi);
			cropProc = cropProc.crop(); //Crop the picture to the size of the ROI (still rectangular)
			FloatProcessor psID = new FloatProcessor(cropProc.getWidth(), cropProc.getHeight()); //Create a new float ImageProcessor of the size of the cropped previous one
			psID = cropProc.convertToFloatProcessor(); //Copy the first ImageProcessor into the new one, as float
			psID.add(- mean); //Subtract the measured mean to the whole picture
			ImageProcessor psID2 = psID.createProcessor((int) FFTsize, (int) FFTsize); //Create a new processor with the 2^n size
			xOff = ((int) FFTsize - cropProc.getWidth()) / 2;
			yOff = ((int) FFTsize - cropProc.getHeight()) / 2;
			psID2.setValue(0.0); //Fill the new picture with an intensity of 0.0
			psID2.fill();
			psID2.insert(psID, xOff, yOff); //Insert the previous image in the new one
			psImg = new ImagePlus("resized", psID2); //Create a new ImagePlus from psID2
			psImg.setRoi(croi2); //Set the new ROI on the new ImagePlus
			Roi roi = psImg.getRoi(); //Source code from "Make Inverse" menu -->
			ShapeRoi s1, s2;
			if (roi instanceof ShapeRoi)
				s1 = (ShapeRoi)roi;
			else
				s1 = new ShapeRoi(roi);
			s2 = new ShapeRoi(new Roi(0,0, imp.getWidth(), imp.getHeight()));
			Undo.setup(Undo.ROI, imp);
			if(s1.xor(s2) == null)
			{
				psID2.setRoi(s1.xor(s2)); //<--
				psID2.setValue(0.0); //Set the intensity inside the new ROI to 0.0
				psID2.fill(psID2.getMask());
			}
		}
		else if (roiShape == "Circle")
		{
			imp.setRoi(oroi); //Set the ROI on the ImagePlus
			mean = imp.getStatistics().mean; //Calculate the mean of intensity in the ROI
			cropProc.setRoi(oroi); //Set the ROI on the ImageProcessor
			cropProc = cropProc.crop(); //Crop the picture to the size of the ROI (still rectangular)
			FloatProcessor psID = new FloatProcessor(cropProc.getWidth(), cropProc.getHeight()); //Create a new float ImageProcessor of the size of the cropped previous one
			psID = cropProc.convertToFloatProcessor(); //Copy the first ImageProcessor into the new one, as float
			psID.add(- mean); //Subtract the measured mean to the whole picture
			ImageProcessor psID2 = psID.createProcessor((int) FFTsize, (int) FFTsize); //Create a new processor with the 2^n size
			xOff = ((int) FFTsize - cropProc.getWidth()) / 2;
			yOff = ((int) FFTsize - cropProc.getHeight()) / 2;
			psID2.setValue(0.0); //Fill the new picture with an intensity of 0.0
			psID2.fill();
			psID2.insert(psID, xOff, yOff); //Insert the previous image in the new one
			psImg = new ImagePlus("resized", psID2); //Create a new ImagePlus from psID2
			psImg.setRoi(oroi2); //Set the new ROI on the new ImagePlus
			Roi roi = psImg.getRoi(); //Source code from "Make Inverse" menu -->
			ShapeRoi s1, s2;
			if (roi instanceof ShapeRoi)
				s1 = (ShapeRoi)roi;
			else
				s1 = new ShapeRoi(roi);
			s2 = new ShapeRoi(new Roi(0,0, FFTsize, FFTsize));
			Undo.setup(Undo.ROI, imp);
			psID2.setRoi(s2.not(s1)); //Correction of the initial code in the case of a circular ROI
			psID2.setValue(0.0); //Set the intensity inside the new ROI to 0.0
			psID2.fill(psID2.getMask());
		}
		
		return psImg;
	}

	//---------##
	// UNKNOWN ##
	//---------##

	private ExtensionDescriptor[] extensions =
	{
		ExtensionDescriptor.newDescriptor("getXValue"	, this, ARG_NUMBER),
		ExtensionDescriptor.newDescriptor("getYValue"	, this, ARG_NUMBER, ARG_NUMBER),
		ExtensionDescriptor.newDescriptor("getBinSize"	, this),
		ExtensionDescriptor.newDescriptor("getStackSize", this),
	};

	public ExtensionDescriptor[] getExtensionFunctions()
	{
		return extensions;
	}

	public String handleExtension(String name, Object[] args)
	{
		if (name.equals("getXValue"))
		{
			int pos = ( (Double) args[0] ).intValue();
			return Double.toString(dataX[pos]);
		}
		else if (name.equals("getYValue"))
		{
			int pos0 = ( (Double) args[0] ).intValue();
			int pos1 = ( (Double) args[1] ).intValue();
			return Double.toString(dataY[pos0][pos1]);
		}
		else if (name.equals("getBinSize"))
		{
			return Integer.toString(nBins);
		}
		else if (name.equals("getStackSize"))
		{
			return Integer.toString(imp.getStackSize());
		}

		return null;
	}

	//------------------------------------------##
	// GRAPHIC USER INTERFACE RELATED FUNCTIONS ##
	//------------------------------------------##

	// GENERATE THE GRAPHIC USER INTERFACE FOR THE PLUG-IN
	@SuppressWarnings("unchecked")
	private boolean getParams()
	{
		gd = new NonBlockingGenericDialog("Autocorrelation on [" + imp.getWindow().getTitle() + "]");
		gd.addNumericField	("X_center (pixels):"           , X0, 2);
		gd.addNumericField	("Y_center (pixels):"           , Y0, 2);
		gd.addNumericField	("Radius (pixels):  "           , mR, 2);
		gd.addRadioButtonGroup	(null, shapeRoi, 2, 1, "Circle");
		gd.addCheckbox		("Use_Time_Scale", useTimeCalibration);
		gd.addCheckbox		("Use_Spatial_Calibration", useCalibration);
		gd.addPanel		(addPanel());
		gd.addRadioButtonGroup (null, type, 2, 1, "ACF on Pixels");
		gd.addPanel		(addPanel2());
		gd.addCheckbox		("Use_FFT_(faster)", useFFT);
		gd.addCheckbox		("Calculate_ACF_on_Stack", makeStackAnalysis);
		gd.addCheckbox		("Calculate_Mean_ACF", makeStackmean);
		gd.addPanel		(addPanel3());
		gd.addChoice		("Step Type", stepType, "Power of 2");
		gd.addChoice		("Wavelength Step", stepList, "4%");
		gd.addCheckbox		("Smooth Bandpass Filter", smoothBandpass);
		gd.addCheckbox		("Display Amplitudes for each modes", displayAmplitude);
		gd.addCheckbox		("Display filtered stack", displayFilter);
		gd.addSlider		("Select Filter", 1.0, (double) resolWav, 1.0);
		gd.addMessage		("--------------------------------");
		gd.addCheckbox		("!!!--DEBUG MODE--!!!", debugMode);
		gd.setOKLabel		("Cancel");
		gd.hideCancelButton();
		gd.addHelp		("http://www.ics-cnrs.unistra.fr/Mcube/spip.php?article238&lang=en");

		numericFields = (TextField[]) (gd.getNumericFields().toArray(new TextField[gd.getNumericFields().size()]));

		Vector checkboxs = gd.getCheckboxes();
		cb0 = (Checkbox)(checkboxs.elementAt(0));
		cb1 = (Checkbox)(checkboxs.elementAt(1));
		cb2 = (Checkbox)(checkboxs.elementAt(2));
		cb3 = (Checkbox)(checkboxs.elementAt(3));
		cb4 = (Checkbox)(checkboxs.elementAt(4));
		cb5 = (Checkbox)(checkboxs.elementAt(5));
		cb6 = (Checkbox)(checkboxs.elementAt(6));
		cb7 = (Checkbox)(checkboxs.elementAt(7));
		cb8 = (Checkbox)(checkboxs.elementAt(8));
		
		Vector slider = gd.getSliders();
		slider0 = (Scrollbar)(slider.elementAt(0));
		
		Vector choices = gd.getChoices();
		choice0 = (Choice)(choices.elementAt(0));
		choice1 = (Choice)(choices.elementAt(1));
		
		if (imp.getStackSize() > 1)
		{
			button0.setEnabled(true);
			button2.setEnabled(true);
			cb0.setEnabled(true);
			cb3.setEnabled(true);
			cb5.setEnabled(true);
			cb6.setEnabled(true);
			cb7.setEnabled(true);
			choice0.setEnabled(true);
		}
		else
		{
			button0.setEnabled(false);
			button2.setEnabled(false);
			cb0.setEnabled(false);
			cb3.setEnabled(false);
			cb5.setEnabled(false);
			cb6.setEnabled(false);
			cb7.setEnabled(false);
			choice0.setEnabled(false);
		}		

		plotROI();
		gd.addDialogListener(this);
		gd.addMouseWheelListener(this);
		gd.showDialog();

		if (gd.wasCanceled())
		{
			imp.setOverlay(null);
			return false;
		}

		return true;
	}
	
	// EDIT THE PARAMETERS AND THE GUI IF SOMETHING IS MODIFIED
	public boolean dialogItemChanged(GenericDialog gd, AWTEvent e)
	{	
		X0					=		gd.getNextNumber();
		Y0					=		gd.getNextNumber();
		mR					=		gd.getNextNumber();
		roiShape			=		gd.getNextRadioButton();
		useTimeCalibration	=		gd.getNextBoolean();
		useCalibration		=		gd.getNextBoolean();
		typeACF				=		gd.getNextRadioButton();
		useFFT				=		gd.getNextBoolean();
		makeStackAnalysis	=		gd.getNextBoolean();
		makeStackmean		=		gd.getNextBoolean();
		typeStep			=		gd.getNextChoice();
		waveStep			=		gd.getNextChoice();
		smoothBandpass		=		gd.getNextBoolean();
		displayAmplitude	=		gd.getNextBoolean();
		displayFilter		=		gd.getNextBoolean();
		debugMode			=		gd.getNextBoolean();

		displayFiltNumber	=		slider0.getValue();
		
		if(makeStackAnalysis == false || imp.getStackSize() <= 1)
		{
			makeStackmean = false;
			cb4.setEnabled(false);
		}
		else
		{
			cb4.setEnabled(true);
		}
		
		if(typeStep == "Power of 2" || imp.getStackSize() == 0)
			choice1.setEnabled(false);
		else
			choice1.setEnabled(true);
		
		if(displayFilter == false || imp.getStackSize() <= 1)
		{
			slider0.setEnabled(false);
			slider0.setFocusable(false);
		}
		else
		{
			slider0.setEnabled(true);
			slider0.setFocusable(true);
		}
		
		if(gd.invalidNumber())
		{
			IJ.beep();
			return false;
		}
		
		if(typeStep == "Power of 2")
		{
			double minFFTsize = 2 * mR, FFTsize = 4;
			int powCount = 1;
			while (FFTsize < minFFTsize)
			{
				FFTsize = FFTsize * 2; //Set the size of the processed picture to 2^n * 2^n, where 2^n is bigger than the ROI size
				powCount = powCount + 1;
			}
			slider0.setMaximum(powCount + 1);
		}
		else
		{
			if(waveStep == "2%")
				resolWav = 50;
			else if(waveStep == "4%")
				resolWav = 25;
			else if(waveStep == "5%")
				resolWav = 20;
			else if(waveStep == "10%")
				resolWav = 10;
			else if(waveStep == "20%")
				resolWav = 5;
			else if(waveStep == "25%")
				resolWav = 4;
			else if(waveStep == "50%")
				resolWav = 2;
			slider0.setMaximum(resolWav + 1);
		}
		
		correctValues();
		plotROI();
		return true;
	}
	
	// ADD THE DIFFERENT BUTTON OF THE GUI
	private Panel addPanel()
	{
		Panel panel = new Panel();
		panel.setLayout(new GridLayout(1, 1));
		button0 = new Button("Calculate ACF over time (g)");
		button0.addActionListener(this);
		panel.add(button0);

		return panel;
	}
	private Panel addPanel2()
	{
		Panel panel2 = new Panel();
		panel2.setLayout(new GridLayout(1, 1));
		button1 = new Button("Calculate ACF over radius (q)");
		button1.addActionListener(this);
		panel2.add(button1);

		return panel2;
	}
	private Panel addPanel3()
	{
		Panel panel3 = new Panel();
		panel3.setLayout(new GridLayout(1, 1));
		button2 = new Button("Calculate ACF over time and modes");
		button2.addActionListener(this);
		panel3.add(button2);

		return panel3;
	}

	// SET THE PARAMETERS AND BOOLEAN USED IN THE GRAPHIC USER INTERFACE
	public void setParams(double X_Center, double Y_Center, double Radius, boolean Pixel_Intensity, boolean Pixel_Mean, boolean Use_FFT, boolean Use_Calibration, boolean Make_Stack_Analysis, boolean Make_Stack_Mean)
	{
 		X0			= X_Center;
 		Y0			= Y_Center;
 		mR			= Radius;
		useFFT				= Use_FFT;
		useCalibration		= Use_Calibration;
		makeStackAnalysis	= Make_Stack_Analysis;
		makeStackmean		= Make_Stack_Mean;
	}

	// CALL THE PLOT ROI FUNCTION /!\ <- CHECK IF NECESSARY
	private void plotROI()
	{
		plotIntegrationROI();
	}

	// PLOT THE ROI ON THE PICTURE
	private void plotIntegrationROI()
	{
		imp.setOverlay(null);

		for(int i = -2; i <= 2; i++)
		{
			xPoint[i+2] = (int) (X0 + 2 * mR * cos(Math.PI * 0.5 * i));
			yPoint[i+2] = (int) (Y0 - 2 * mR * sin(Math.PI * 0.5 * i));
		}
		xPoint[5] = (int) X0;
		yPoint[5] = (int) Y0;
		
		s1 = new ShapeRoi(new PolygonRoi(xPoint, yPoint, 6,  Roi.POLYGON));
		if(roiShape == "Circle")
			s2 = new ShapeRoi(new OvalRoi((int)(X0 - mR), (int)(Y0 - mR), (int)(2 * mR), (int)(2 * mR)));
		else
			s2 = new ShapeRoi(new Roi((int)(X0 - mR), (int)(Y0 - mR), (int)(2 * mR), (int)(2 * mR)));
		s1.and(s2);
		imp.setRoi(s1);
		imp.repaintWindow();
	}

	//--------------------------------##
	// GENERIC MATHEMATICAL FUNCTIONS ##
	//--------------------------------##

	// CALCULATE HOW MUCH DIGITS HAS TO BE DISPLAYED
	public int getDigits(double n1, double n2) {
		if (Math.round(n1)==n1 && Math.round(n2)==n2)
			return 0;
		else {
			n1 = Math.abs(n1);
			n2 = Math.abs(n2);
			double n = n1<n2&&n1>0.0?n1:n2;
			double diff = Math.abs(n2-n1);
			if (diff>0.0 && diff<n) n = diff;
			int digits = 3;
			if (n<10.0) digits = 4;
			if (n<0.01) digits = 5;
			if (n<0.001) digits = 6;
			if (n<0.0001) digits = 7;	    
			return digits;
		}
	}

	// GET THE MINIMUM SIZE OF THE EQUIVALENT PICTURE FOR FFT
	private double findFourrierSize(double minSize)
	{
		double FFTsize =4;
		while (FFTsize < minSize) FFTsize = FFTsize * 2;
		return FFTsize;
	}

	// GENERATE COLORS FOR MULTI GRAPH PLOT
	public int colorGeneration(int val, int axis)
	{
		return(Math.IEEEremainder((double) (val - axis), 4.0) == 0.0)?1:0;
	}

	// CALCULATE THE COSINE OF THE ANGLE
	public double cos(double val)
	{
		if(Math.IEEEremainder(val, 2.0 * Math.PI) == 0.0)
			return 1.0;
		else if(Math.IEEEremainder(val, Math.PI) == 0.0)
			return -1.0;
		else if(Math.IEEEremainder(val, Math.PI / 2.0) == 0.0)
			return 0.0;
		else
			return Math.cos(val);
	}

	// CALCULATE THE SINE OF THE ANGLE
	public double sin(double val)
	{
		return(Math.IEEEremainder(val, Math.PI) == 0.0)?0.0:Math.sin(val);
	}
	
	// RETURN THE ABSOLUTE VALUE OF THE RADIUS
	private void correctValues()
	{
		if(mR < 0)
		{
			mR = -mR;
			numericFields[RADIUS].setText(IJ.d2s(mR, 2));
		}
	}

/*#############################################################################
*  OUTDATED - TO REMOVE AFTER FINAL TEST
*
*	public int colorX(int val)
*	{
*		return(Math.IEEEremainder((double) (val - 1), 4.0) == 0.0)?1:0;
*	}
*
*	public int colorY(int val)
*	{
*		return(Math.IEEEremainder((double) (val - 2), 4.0) == 0.0)?1:0;
*	}
*
*	public int colorZ(int val)
*	{
*		return(Math.IEEEremainder((double) (val - 3), 4.0) == 0.0)?1:0;
*	}
*##############################################################################
*/

	//-------------------------------------------------##
	// LISTENERS USED BY THE PLUG-IN FOR GUINTERFACING ##
	//-------------------------------------------------##

	// LISTENER ON ACTION PERFORMED /!\ <- CHECK THE UTILITY OF THE FUNCTION
	public void actionPerformed(ActionEvent e)
	{
		Object b = e.getSource();

		if (b == button0)
			calculateOverTime();
		else if (b == button1)
			calculateRadialAutoCorrelation();
		else if (b == button2)
			testWaveACF();
	}
	
	// LISTENER ON THE KEYBOARD
	public void keyPressed(KeyEvent e)
	{
		int keyCode = e.getKeyCode();
		int flags   = e.getModifiers();
		e.consume();

		if (keyCode == KeyEvent.VK_RIGHT || keyCode == KeyEvent.VK_NUMPAD6)
		{ 
			if (flags == KeyEvent.SHIFT_MASK)
				X0 += 5;
			else if (flags == KeyEvent.CTRL_MASK)
				X0 += 10;
			else
				X0++;
			numericFields[X_CENTER].setText(IJ.d2s(X0, 2));
			imp.repaintWindow();
			plotROI();
		}
		else if (keyCode == KeyEvent.VK_LEFT || keyCode == KeyEvent.VK_NUMPAD4)
		{ 
			if (flags == KeyEvent.SHIFT_MASK)
				X0 -= 5;
			else if (flags == KeyEvent.CTRL_MASK)
				X0 -= 10;
			else
				X0--;
			numericFields[X_CENTER].setText(IJ.d2s(X0, 2));
			imp.repaintWindow();
			plotROI();
		}
		else if (keyCode == KeyEvent.VK_DOWN || keyCode == KeyEvent.VK_NUMPAD2)
		{ 
			if (flags == KeyEvent.SHIFT_MASK)
				Y0 += 5;
			else if (flags == KeyEvent.CTRL_MASK)
				Y0 += 10;
			else
				Y0++;
			numericFields[Y_CENTER].setText(IJ.d2s(Y0, 2));
			imp.repaintWindow();
			plotROI();			
		}
		else if (keyCode == KeyEvent.VK_UP || keyCode == KeyEvent.VK_NUMPAD8)
		{ 
			if (flags == KeyEvent.SHIFT_MASK)
				Y0 -= 5;
			else if (flags == KeyEvent.CTRL_MASK)
				Y0 -= 10;
			else
				Y0--;
			numericFields[Y_CENTER].setText(IJ.d2s(Y0, 2));
			imp.repaintWindow();
			plotROI();		
		}
		else if (keyCode== KeyEvent.VK_PAGE_UP || keyCode==e.VK_ADD)
		{
			if((e.getModifiersEx() & alt_mask) != alt_mask)
			{
				if(flags == KeyEvent.CTRL_MASK)
					mR += 10;
				else if(flags == KeyEvent.SHIFT_MASK)
					mR += 5;
				else
					mR ++;
				numericFields[RADIUS].setText(IJ.d2s(mR, 2));
			}
			imp.repaintWindow();
			plotROI();
		}
		else if (keyCode == KeyEvent.VK_PAGE_DOWN || keyCode == e.VK_SUBTRACT)
		{
			if((e.getModifiersEx() & alt_mask) != alt_mask)
			{
				if(flags == KeyEvent.CTRL_MASK)
					mR = (mR >= 10) ? (mR - 10) : mR;
				else if(flags == KeyEvent.SHIFT_MASK)
					mR = (mR >=  5) ? (mR -  5) : mR;
				else
					mR = (mR >=  1) ? (mR -  1) : mR;
				numericFields[RADIUS].setText(IJ.d2s(mR, 2));
			}
			imp.repaintWindow();
			plotROI();
		}
		else if (keyCode == e.VK_G)
			calculateOverTime();
		else if (keyCode == e.VK_Q)
			calculateRadialAutoCorrelation();
	}

	// MODIFY THE ROI CENTER ON A MOUSE DRAG
	public void mouseDragged(MouseEvent e)
	{
		X0 = canvas.offScreenX(e.getX());
		Y0 = canvas.offScreenY(e.getY());
		numericFields[X_CENTER].setText(IJ.d2s(X0, 2));
		numericFields[Y_CENTER].setText(IJ.d2s(Y0, 2));
		imp.repaintWindow();
		plotROI();
	}

	// MODIFY THE VALUES ON A MOUSE WHEEL TURN
	public void mouseWheelMoved(MouseWheelEvent e)
	{
		e.consume();

		for (int i = 0; i < numericFields.length; i++)			// mouseWheelEvents for numeric input fields
		{
			if(numericFields[i].isFocusOwner())
			{
				mouseWheelOnNumericField(e, i);
				return;						// The MouseWheelEvent was used, no more action
			}
		}
		if (IJ.altKeyDown())						// mouseWheelEvents not for a numeric field: modify the radius or angle
			mouseWheelOnNumericField(e, INT_ANGLE);
		else
			mouseWheelOnNumericField(e, RADIUS);
	}

	// MODIFY A NUMERIC VALUES IN A TEXT FIELD ON A MOUSE WHEEL TURN
	void mouseWheelOnNumericField(MouseWheelEvent e, int fieldIndex)
	{
		double value = Tools.parseDouble(numericFields[fieldIndex].getText());
		if (Double.isNaN(value))
			return;							// invalid number, can't increment/decrement
		int step = 1;
		if ((e.getModifiersEx() & (shift_button_mask | ctrl_mask)) == ctrl_mask)
			step = 10;
		else if (IJ.shiftKeyDown())
			step = 5;
		value -= step * e.getWheelRotation();
		if(fieldIndex <= 2)
			numericFields[fieldIndex].setText(IJ.d2s(value, 2));
		else
			numericFields[fieldIndex].setText(IJ.d2s(value, 0));
		return;
	}

	// REMOVE ALL THE LISTENERS FROM THE PLUGIN
	private void removeListeners(ImagePlus imp)
	{
		ImageWindow win = imp.getWindow();
		if (win == null) return;
		win.removeKeyListener(this);
		canvas = win.getCanvas();
		canvas.removeKeyListener(this);
		canvas.removeMouseMotionListener(this);
		canvas.removeMouseWheelListener(this);
		Prefs.requireControlKey = previousRequireControlKeyState;
		instance = null;
	}

	// REMOVE LISTENERS AND GUI WHEN THE PICTURE IS CLOSED
	public void imageClosed(ImagePlus imp)
	{
		if (imp == this.imp)
		{
			removeListeners(imp);
			gd.dispose();
		}
	}

	// EMPTY LISTENERS - FOR OVERLOADING
	public void imageOpened(ImagePlus imp)
	{
	}
	
	public void imageUpdated(ImagePlus imp)
	{
	}
	
	public void keyReleased(KeyEvent e)
	{
	}

	public void keyTyped(KeyEvent e)
	{
	}
	
	public void mouseMoved(MouseEvent e)
	{
	}

	//----------------------------##
	// DESCRIPTION OF THE PLUG-IN ##
	//----------------------------##

	public void showAbout()
	{
		IJ.showMessage("AutoCorrelation Function...",
			"This plugin produces a plot of the mean AutoCorrelation Function (ACF) of every pixel\n" +
			"intensity over time (in a stack) or the radial averaged AutoCorrelation Function for one\n" +
			"picture or for a whole stack\n" +
			"The plugin performs the calculation inside an circle ROI defined by the user.\n" +
			"                                                                                                                                               \n" +
			"The plugin implements several options for the calculation of the radial ACF:\n" +
			"- For one picture, the calculation can be done using the FFT (faster) or with the naive\n" +
			"autocorrelation function (slower, but more accurate). When calculating over a stack, only\n" +
			"the FFT method is available.\n" +
			"- User can decide if the radius should be converted into pixel or scale set for the picture.\n" +
			"- For the calculation of the radial ACF on a stack, the result plotted can be the ACF of every\n" +
			"slice of the stack, or the mean ACF of the whole stack.\n" +
			"For pixels' ACF over time, the plugin can return the evolution of intensity of every pixel over time.\n" +
			"                                                                                                                                               \n" +
			"This plugin is a mod of the Radial Profile Extended plugin from Philippe CARL and use\n" +
			"most of the features implemented in it.\n" +
			"http://rsb.info.nih.gov/ij/plugins/radial-profile-ext.html \n" +
			"                                                                                                                                               \n" +
			"The Radial averaged Autocorrelation calculation using the FFT is based on the macro\n" +
			"Radially Averaged Autocorrelation from Michael SCHMID\n" +
			"http://imagejdocu.tudor.lu/doku.php?id=macro:radially_averaged_autocorrelation \n" +
			"                                                                                                                                               \n" +
			"First version: 04-28-2015\n" +
			"Author : Vivien WALTER (walter.vivien@gmail.com)"
		);
	}

}

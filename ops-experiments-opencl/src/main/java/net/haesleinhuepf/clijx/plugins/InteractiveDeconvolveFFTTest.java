
package net.haesleinhuepf.clijx.plugins;

import java.io.IOException;

import net.haesleinhuepf.clij.CLIJ;
import net.haesleinhuepf.clij.clearcl.ClearCLBuffer;
import net.haesleinhuepf.clij2.CLIJ2;
import net.imagej.Dataset;
import net.imagej.ImageJ;
import net.imagej.ops.Ops;
import net.imagej.ops.experiments.ImageUtility;
import net.imagej.ops.experiments.testImages.Bars;
import net.imagej.ops.experiments.testImages.DeconvolutionTestData;
import net.imglib2.FinalDimensions;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.outofbounds.OutOfBoundsMirrorFactory;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.util.Intervals;
import net.imglib2.view.Views;
import net.imagej.ops.filter.pad.DefaultPadInputFFT;

public class InteractiveDeconvolveFFTTest<T extends RealType<T> & NativeType<T>> {

	final static ImageJ ij = new ImageJ();

	public static <T extends RealType<T> & NativeType<T>> void main(
		final String[] args) throws IOException
	{
		// check the library path, can be useful for debugging
		System.out.println(System.getProperty("java.library.path"));

		// launch IJ so we can interact with the inputs and outputs
		ij.launch(args);

		// test names
		Dataset testData = (Dataset) ij.io().open(
			"/home/bnorthan/Images/Deconvolution/CElegans_April_2020/CElegans-CY3.tif");
		Dataset psf = (Dataset) ij.io().open(
			"/home/bnorthan/Images/Deconvolution/CElegans_April_2020/PSF-CElegans-CY3.tif");

		// open the test data
		RandomAccessibleInterval<FloatType> imgF = (RandomAccessibleInterval) (ij
			.op().convert().float32((Img) testData.getImgPlus().getImg()));
		
		RandomAccessibleInterval<FloatType> psfF = (RandomAccessibleInterval) (ij
			.op().convert().float32((Img) psf.getImgPlus()));

		// crop PSF - the image will be extended using PSF size
		// if the PSF size is too large it will explode image size, memory needed and processing speed
		// so crop just small enough to capture significant signal of PSF 
		psfF = ImageUtility.cropSymmetric(psfF,
				new long[] { 64, 64, 41 }, ij.op());

		// subtract min from PSF		
		psfF = Views.zeroMin(ImageUtility.subtractMin(psfF, ij.op()));

		// normalize PSF
		psfF = Views.zeroMin(ImageUtility.normalize(psfF, ij.op()));

		// compute extended dimensions based on image and PSF dimensions
		long[] extendedSize = new long[imgF.numDimensions()];

		for (int d = 0; d < imgF.numDimensions(); d++) {
			extendedSize[d] = imgF.dimension(d) + psfF.dimension(d);
		}

		FinalDimensions extendedDimensions = new FinalDimensions(extendedSize);

		// extend image
		RandomAccessibleInterval<FloatType> extended = (RandomAccessibleInterval) ij
			.op().run(DefaultPadInputFFT.class, imgF, extendedDimensions, false,
				new OutOfBoundsMirrorFactory(OutOfBoundsMirrorFactory.Boundary.SINGLE));
 
		// show image and PSF
		ij.ui().show("img ", imgF);
		ij.ui().show("psf ", psfF);
		
		// get clij
		CLIJ2 clij2 = CLIJ2.getInstance("RTX");

		// push extended image and psf to GPU
		ClearCLBuffer inputGPU = clij2.push(extended);
		ClearCLBuffer psfGPU = clij2.push(psfF);

		// create output
		ClearCLBuffer output = clij2.create(inputGPU);

		// deconvolve
		DeconvolveFFT.deconvolveFFT(clij2, inputGPU, psfGPU, output);
		
		// get deconvolved as an RAI
		RandomAccessibleInterval deconv=clij2.pullRAI(output);
		
		// create unpadding interval
		Interval interval = Intervals.createMinMax(-extended.min(0), -extended
			.min(1), -extended.min(2), -extended.min(0) + imgF.dimension(0) -
				1, -extended.min(1) + imgF.dimension(1) - 1, -extended.min(2) +
					imgF.dimension(2) - 1);

		// create an RAI for the output... we could just use a View to unpad, but performance for slicing is slow 
		RandomAccessibleInterval outputRAI=ij.op().create().img(imgF);
		
		// copy the unpadded interval back to original size
		ij.op().run(Ops.Copy.RAI.class, outputRAI, Views.zeroMin(Views.interval(deconv,
			interval)));
		
		//clij2.show(output, "output");
		ij.ui().show("deconvolved and unpadded", outputRAI);

	}
}


package net.imagej.ops.experiments;

import net.imagej.ops.OpService;
import net.imagej.ops.Ops;
import net.imagej.ops.experiments.filter.deconvolve.CudaDeconvolutionUtility;
import net.imagej.ops.experiments.filter.deconvolve.YacuDecuRichardsonLucyOp;
import net.imagej.ops.experiments.filter.deconvolve.YacuDecuRichardsonLucyWrapper;
import net.imagej.ops.filter.fftSize.DefaultComputeFFTSize;
import net.imagej.ops.special.computer.AbstractUnaryComputerOp;
import net.imagej.ops.special.function.BinaryFunctionOp;
import net.imagej.ops.special.function.Functions;
import net.imglib2.Cursor;
import net.imglib2.FinalDimensions;
import net.imglib2.FinalInterval;
import net.imglib2.IterableInterval;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.type.numeric.real.FloatType;
import net.imglib2.view.Views;

import org.bytedeco.javacpp.FloatPointer;
import org.scijava.Priority;
import org.scijava.log.LogService;
import org.scijava.plugin.Parameter;
import org.scijava.plugin.Plugin;
import org.scijava.ui.UIService;

import net.imglib2.outofbounds.OutOfBoundsConstantValue;
import net.imglib2.outofbounds.OutOfBoundsConstantValueFactory;

/**
 * Wrap Richardson Lucy Cuda deconvolution in a UnaryComputerOp so we can run it
 * within the imglib2 cache framework.
 * 
 * @author bnorthan
 */
@Plugin(type = Ops.Deconvolve.RichardsonLucy.class, priority = Priority.LOW)
public class UnaryComputerYacuDecuNC extends
	AbstractUnaryComputerOp<RandomAccessibleInterval<FloatType>, RandomAccessibleInterval<FloatType>>
{

	@Parameter
	OpService ops;

	@Parameter
	UIService ui;

	@Parameter
	LogService log;

	@Parameter
	RandomAccessibleInterval<FloatType> psf;

	@Parameter
	int iterations;

	@Override
	public void compute(final RandomAccessibleInterval<FloatType> input,
		final RandomAccessibleInterval<FloatType> output)
	{

		log.info("min: " + output.min(0) + " " + output.min(1) + " " + output.min(
			2));
		log.info("max: " + output.max(0) + " " + output.max(1) + " " + output.max(
			2));

		// min and max of the cell we are generating data for
		final long[] min = new long[] { output.min(0), output.min(1), output.min(
			2) };
		final long[] max = new long[] { output.max(0), output.max(1), output.max(
			2) };

		// compute extended size of the image based on PSF dimension
		final long[] extendedSize = new long[output.numDimensions()];

		for (int d = 0; d < output.numDimensions(); d++) {
			extendedSize[d] = output.dimension(d) + psf.dimension(d);
		}

		// compute fast FFT extended size
		long[][] fastExtendedSize = (long[][]) ops.run(DefaultComputeFFTSize.class,
			new FinalDimensions(extendedSize), false);
		FinalDimensions fastExtendedDimensions = new FinalDimensions(
			fastExtendedSize[0]);

		// compute extended min and max to obtain a region with size
		// fastExtendedSize
		final long[] minExtendedInput = new long[input.numDimensions()];
		final long[] maxExtendedInput = new long[input.numDimensions()];

		for (int d = 0; d < psf.numDimensions(); d++) {
			minExtendedInput[d] = min[d] - (fastExtendedDimensions.dimension(d) -
				output.dimension(d)) / 2;
			maxExtendedInput[d] = minExtendedInput[d] + fastExtendedDimensions
				.dimension(d) - 1;
		}

		FinalInterval inputInterval = new FinalInterval(min, max);

		FinalInterval extendedInterval = new FinalInterval(minExtendedInput,
			maxExtendedInput);

		IterableInterval<FloatType> inputIterable = Views.zeroMin(Views.interval(
			Views.extendMirrorSingle(input), inputInterval));

		Img<FloatType> inputCopy = ops.create().img(inputInterval, new FloatType());
		
		ops.copy().rai(inputCopy,Views.interval(input, inputInterval));
		
		ui.show("copy", inputCopy);

		@SuppressWarnings("unchecked")
		RandomAccessibleInterval<FloatType> paddedInput = ops.filter().padInput(
			inputCopy, fastExtendedDimensions, new OutOfBoundsConstantValueFactory(
				new FloatType()));
		
		paddedInput=Views.zeroMin(paddedInput);
		
		RandomAccessibleInterval<FloatType> paddedPSF = Views.zeroMin(ops.filter()
			.padShiftFFTKernel(psf, fastExtendedDimensions));

		ui.show("paddedInput", paddedInput);
		ui.show("paddedPSF", paddedPSF);

		// copy to GPU memory
		YacuDecuRichardsonLucyWrapper.load();

		FloatPointer fpInput = null;
		FloatPointer fpPSF = null;
		FloatPointer fpOutput = null;

		// convert image to FloatPointer
		
		fpInput = ConvertersUtility.ii3DToFloatPointer(Views.iterable((paddedInput)));

		// convert PSF to FloatPointer
		// fpPSF = ConvertersUtility.ii3DToFloatPointer(Views.zeroMin(Views
		// .interval(Views.extendZero(psf), psfInterval)));

		fpPSF = ConvertersUtility.ii3DToFloatPointer(Views.zeroMin(paddedPSF));

		// convert image to FloatPointer
		
		int paddedSize = (int) (paddedInput.dimension(0) * paddedInput
				.dimension(1) * paddedInput.dimension(2));

		float mean=ops.stats().mean(Views.iterable(input)).getRealFloat()/paddedSize;
		
		fpOutput = new FloatPointer(paddedSize);
		
		for (int i=0;i<paddedSize;i++) {
			fpOutput.put(i,mean);
		}
		
		FloatPointer normalFP=CudaDeconvolutionUtility.createNormalizationFactor(ops, paddedInput, output,
			fpPSF,null,null);	

		final long startTime = System.currentTimeMillis();

		// Call the Cuda wrapper
		YacuDecuRichardsonLucyWrapper.deconv_device(iterations, (int) paddedInput
			.dimension(2), (int) paddedInput.dimension(1), (int) paddedInput
				.dimension(0), fpInput, fpPSF, fpOutput, normalFP);

		final long endTime = System.currentTimeMillis();

		// copy output to array
		final float[] arrayOutput = new float[paddedSize];
		fpOutput.get(arrayOutput);

		final Img<FloatType> deconv = ArrayImgs.floats(arrayOutput, new long[] {
			paddedInput.dimension(0), paddedInput.dimension(1), paddedInput
				.dimension(2) });

		ui.show("deconv", deconv);

		// copy the extended deconvolution to the original cell
		Cursor<FloatType> c1 = Views.iterable(Views.zeroMin(output)).cursor();

		RandomAccessibleInterval<FloatType> r = Views.zeroMin(Views.interval(deconv,
			new FinalInterval(new long[] { min[0] - minExtendedInput[0], min[1] -
				minExtendedInput[1], min[2] - minExtendedInput[2] }, new long[] { output
					.dimension(0) - 1, output.dimension(1) - 1, output.dimension(2) -
						1 })));

		RandomAccess<FloatType> ra = r.randomAccess();

		c1.fwd();

		while (c1.hasNext()) {

			ra.setPosition(c1);

			// set the value of this pixel of the output image, every Type supports
			// T.set( T type )
			c1.get().set(ra.get());
			c1.fwd();

		}

		/*
		ops().copy().rai(Views.zeroMin(output), Views.interval(deconv,
			new FinalInterval(new long[] { 0, 0, 0 }, new long[] { output.dimension(
				0) - 1, output.dimension(2) - 1, output.dimension(2) - 1 })));*/

	}
}

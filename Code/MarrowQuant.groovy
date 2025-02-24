// = CODE DESCRIPTION =
// This series of scripts aims to quantify 4 parameters from H&E stained
// bone sections. Bone, Adipocytes, Hematopoietic Cells and Interstitium
// and microvasculature.
// 
// == INPUTS ==
// To run this code, you need to create multiple annotations in QuPath:
// 1. Annotation of class "Tissue Boundaries" which will contain the 
//    region to analyze.
// 2. Rectangle of class "BG" containing background-only for color
//    balancing
// 3. 1 or more Annotations called "Artifact" marking areas inside 
//    "Tissue Boundaries" that should be excluded from quantification.
// 
// == OUTPUTS ==
// The code produces multiple detections to illustrate the detected areas
// the Detections are called Bone, Adipocytes, IMV, HematoCells.
// Results of quantification as per the MarrowQuant paper are located as
// measurements in the "Tissue Boundaries" annotation.
//
// = DEPENDENCIES =
// MarrowQuant needs several jars to run properly. Please see the
// installation instruction on GitHub: 
// https://github.com/Naveiras-Lab/MarrowQuant/tree/qupath-0.1.4
// 
// 
// = AUTHOR INFORMATION =
// Code initially written by Chiheb Boussema & Ibrahim Bekri, EPFL-SV-GRNAVEIRAS
// Code debugging, supervision and upgrade by Olivier Burri & Rita Sarkis
// EPFL-SV-PTECH-BIOP, EPFL-SV-GRNAVEIRAS
// for Josefine Tratwal, Naveiras-Lab
// DATE
// 
// = COPYRIGHT =
// © All rights reserved. ECOLE POLYTECHNIQUE FEDERALE DE LAUSANNE, Switzerland, BioImaging And Optics Platform (BIOP), 2018
// 
// Licensed under the BSD-3-Clause License:
// Redistribution and use in source and binary forms, with or without modification, are permitted provided 
// that the following conditions are met:
// 1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
// 2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer 
//    in the documentation and/or other materials provided with the distribution.
// 3. Neither the name of the copyright holder nor the names of its contributors may be used to endorse or promote products 
//     derived from this software without specific prior written permission.
// 
// THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, 
// BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. 
// IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, 
// EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; 
// LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
// STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF 
// ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

// Parameters to set 
def downsample = 4
def adipmin = 120
def adipmax = 1000000000 
def mincir = 0.3
def individual_adip = true 



// Start of Script

all_regions = getAnnotationObjects()
clearDetections()

def tissues = all_regions.findAll{ it.getPathClass() == getPathClass("Tissue Boundaries") }

tissues.eachWithIndex{ tissue, k ->
    def mq = new MQBuilder().assignTissueAnnotation( tissue )
                            .assignTissueNumber( k ) 
                            .assignDownsample( downsample )
                            .assignAdipMin( adipmin )
                            .assignAdipMax ( adipmax )
                            .assignMinCir   ( mincir )
                            .assignExcludeOnEdges ( false )
                            .assignTestAP ( false )
                            .assignGetIndividualAdipocytes ( individual_adip )
                            .create()
    
    mq.run()
}



import ij.*
import ij.process.*
import ij.measure.Measurements
import ij.gui.*
import ij.plugin.ImageCalculator
import ij.plugin.Thresholder
import ij.plugin.filter.ThresholdToSelection
import ij.plugin.filter.ImageMath
import ij.plugin.RGBStackConverter
import ij.plugin.RoiEnlarger
import ij.plugin.frame.RoiManager

import sc.fiji.colourDeconvolution.*

import groovy.transform.ToString
import groovy.transform.builder.Builder
import groovy.transform.builder.ExternalStrategy

import qupath.lib.objects.*
import qupath.imagej.objects.*
import qupath.imagej.helpers.*
import qupath.lib.roi.*
import ch.epfl.biop.qupath.utils.*

import org.slf4j.Logger
import org.slf4j.LoggerFactory

@ToString(includeNames=true)
class MarrowQuant {
    
    def downsample       = 4
    def adipMin 	 = 5
    def adipMax 	 = 100000000
    def minCir           = 0.4
    def excludeOnEdges   = false
    def testAP 	         = false
    def fixNDPIBug       = false
    def testParams       = false
    def getIndividualAdipocytes = true
    
    def backgroundAnnotation
    def mergedArtifactsAnnotation
    def tissueAnnotation
    def tissueNumber
    def image
    
    def artifactsRoi
    def backgroundRoi
    def tissueRoi
    def imvRoi
    def hematoRoi
    def boneRoi
    def adipRoi
   
    def ic
    
    def deconvolved
    
    def imvMaskImage
    def adipMaskImage
    def boneMaskImage
    def hematoMaskImage
    
    final private static Logger logger = LoggerFactory.getLogger( MarrowQuant.class ) 
    
    public void run() {
    
        this.ic = new ImageCalculator()       
        logger.info( "{}"   , this.adipMin )
        
        // Get annotations to use
        this.backgroundAnnotation = getAllObjects().find{ it.getPathClass() == getPathClass("BG") }
        //tissueAnnotation.getChildObjects()
        //tissueAnnotation.getChildObjects().find{ it.getPathClass() == getPathClass("BG") }
        
        this.mergedArtifactsAnnotation = mergeAnnotations( tissueAnnotation.getChildObjects().findAll{ it.getPathClass() == getPathClass("Artifact") } )
        
        println(this.mergedArtifactsAnnotation)

        this.image = GUIUtils.getImagePlus( tissueAnnotation, downsample, false, false )
                
        this.artifactsRoi = getIJRoi( mergedArtifactsAnnotation )
                
        // Do Color Balance
        this.backgroundRoi = getIJRoi( backgroundAnnotation )
        //this.image = colorBalance( image, backgrounRoi )
        
        this.tissueRoi = getIJRoi( tissueAnnotation )
        // Find bone
        
        // Apply Color Deconvolution to get resulting ImagePlus
        this.deconvolved = colorDeconvolution( image, "H&E DAB" )

        boneIMVFinder()        
        
        hematoFinder()
        
        adipFinder()
        
        sendResultsToQuPath()
    }  
    
    public void boneIMVFinder() {
        logger.info( "Bone Finder"  )
                
        // Create new image based on stacks 1 and 2
        def boneIMVImage = this.ic.run( "Subtract create", this.deconvolved[0], this.deconvolved[1] )
        
         //boneIMVImage.show()
         
         def varianceImage = boneIMVImage.duplicate()
         // Create Variance Image
         IJ.run( varianceImage, "32-bit", "" )
         IJ.run( varianceImage, "Variance...", "radius=1")
         IJ.run( varianceImage, "Add...", "value=1")
         
         // IB
	// BUGFIX 19.01.2016 with Oli
	// if needed to fix NDPI bug

	if( fixNDPIBug ) {
		// extra step where we set the Bone IMV background to Not A Number before division
		def boneProc = boneIMVImage.getProcessor()
		boneIMVProc.setRoi( this.tissueRoi )
		
		boneIMVProc.setAutoThreshold("Huang Dark")
		Roi sel = ThresholdToSelection.run( boneIMVImage )

		def inverse = sel.getInverse( boneIMVImage )
		
		boneIMVProc.setRoi( inverse )
		boneIMVProc.resetThreshold()

	}
	// END BUGFIX
	
	def boneImage = this.ic.run("Divide create 32-bit", boneIMVImage, varianceImage )
	
	def boneProc = boneImage.getProcessor()
	boneProc.setRoi( this.tissueRoi )
	boneProc.setAutoThreshold("Triangle dark no-reset")
	

	def boneMaskProc = boneProc.createMask()
	
	this.boneMaskImage = new ImagePlus("Bone Mask", boneMaskProc )

	this.boneMaskImage.setCalibration( image.getCalibration() )
	IJ.run( this.boneMaskImage, "Options...", "iterations=5 count=1 black pad do=Dilate");
	IJ.run( this.boneMaskImage, "Options...", "iterations=15  count=2 black pad do=Close");

	boneMaskProc.setBackgroundValue(0)

	// Clear the artifacts
	boneMaskProc.fill( this.artifactsRoi )
	// Clear the tissue
	boneMaskProc.fillOutside( this.tissueRoi )
	
        this.boneRoi = getRoiFromMask( this.boneMaskImage )
        this.boneMaskImage.setRoi( this.boneRoi )
        //boneMaskImage.show()
	
	
	// Generation of the IMV compartment
	
	def varianceProc = varianceImage.getProcessor()
	
	// Clear outside Tissue
	varianceProc.fillOutside( this.tissueRoi )
	//Clear the Bone area
	varianceProc.fill( this.boneRoi )
	
	varianceProc.setRoi( this.tissueRoi )
	varianceProc.setAutoThreshold("Li dark no-reset")
	def imvMaskProc = varianceProc.createMask()

        this.imvMaskImage = new ImagePlus("IMV", imvMaskProc )
	this.imvRoi = getRoiFromMask( imvMaskImage )
	IJ.run( this.imvMaskImage, "Options...", "iterations=1 count=2 black pad do=Dilate") // best with Li
        //this.imvMaskImage.setRoi( this.imvRoi )
        //imvMaskImage.show()   
    }
    
    def hematoFinder() {

	
	def hematoImage = this.ic.run( "Subtract create", this.deconvolved[2], this.deconvolved[0] )
	
	def hematoProc = hematoImage.getProcessor()
		
	hematoProc.smooth()
	
	hematoProc.setRoi( this.tissueRoi )
	hematoProc.setAutoThreshold("Default dark")
	
	def hematoMaskProc = hematoProc.createMask()
	
        this.hematoMaskImage = new ImagePlus("Hemato Mask", hematoMaskProc )
        
	this.hematoMaskImage.setCalibration( image.getCalibration() )
	IJ.run( this.hematoMaskImage, "Options...", "iterations=2 count=2 black pad do=Dilate") // best with Li
        //IJ.run( hematoMaskImage, "Options...", "iterations=1 count=1 black pad do=Dilate") // best Huang / Mean
      	//IJ.run( hematoMaskImage, "Options...", "iterations=1 count=5 black pad do=Dilate")
	//IJ.run( hematoMaskImage, "Options...", "iterations=1 count=3 black pad do=Dilate")
	// Nothing = best with Li
	
	// Clear outside Tissue
	hematoMaskProc.fillOutside( this.tissueRoi )
	//Clear the Bone area
	hematoMaskProc.fill( this.boneRoi )
	hematoMaskProc.fill( this.artifactsRoi )
	
	        
        //Subtract Hemato Mask to IMV Mask (Avoid Overlap)
        this.ic.run("Subtract", this.imvMaskImage, this.hematoMaskImage )
        // Redo selection of IMV
        this.imvRoi = getRoiFromMask( this.imvMaskImage )
        
	this.hematoRoi = getRoiFromMask( hematoMaskImage )
        //this.hematoMaskImage.setRoi( this.hematoRoi )
        //this.hematoMaskImage.show()
                
}
    public void adipFinder() {
        def hsbImage = this.image.duplicate()
        
        // Convert to HSB
        new ImageConverter( hsbImage ).convertToHSB();
        
        def adipImage = this.ic.run( "Subtract create", deconvolved[2], deconvolved[0] );
	
	// Multiply staturation by 8
	hsbImage.getStack().getProcessor( 2 ).multiply( 8 ) //Oli: This completely saturates the image and should be considered bad practice
	
	this.ic.run( "Add",  adipImage, new ImagePlus( "Saturation", hsbImage.getStack().getProcessor( 2 ) ) )

        //adipImage.show()
        def adipProc = adipImage.getProcessor()
        
        adipProc.setThreshold( 0, 200, ImageProcessor.NO_LUT_UPDATE  ) // Oli: Was 127
        def adipMaskProc = adipProc.createMask()
        
        
	// Makes sure that the particle analyzer will act within the Tissue Boundaries only and
	// with the exception of the bone and artifacts
        // Clear outside Tissue
	adipMaskProc.fillOutside( this.tissueRoi )
	//Clear the Bone area
	adipMaskProc.fill( this.boneRoi )
	adipMaskProc.setBackgroundValue( 255 )
	adipMaskProc.fill( this.artifactsRoi )
	adipMaskProc.setBackgroundValue( 0 )
		
	 // Morphological operations needed to segment individual adipocytes
	 def adipMaskImage = new ImagePlus( "Adip Mask", adipMaskProc )
	 adipMaskImage.setCalibration( this.image.getCalibration() )
	 	 
	IJ.run( adipMaskImage, "Watershed", "")
	IJ.run( adipMaskImage, "Options...", "iterations=50 count=5 pad do=Dilate" )
	
	adipMaskImage.show()
	//IJ.setAutoThreshold( adipMaskImage, "Default" )
	//Performs an enhanced version of Fiji's analyze particle
	//IJ.log("Values: "+this.adipMin+", "+this.adipMax+", "+this.minCir)
        IJ.run( adipMaskImage, "Invert LUT", "");
	IJ.run( adipMaskImage, "Extended Particle Analyzer", "  area="+this.adipMin+"-"+this.adipMax+" circularity="+this.minCir+"-1.00 roundness=0.36-1.00 show=Masks redirect=None keep=None display" )

	//hide()

	// Create Selection from the mask
	def adipMaskImage2 = IJ.getImage()
	adipMaskImage2.unlock()
	//adipMaskImage2.show()
	//adipMaskProc.fill( this.artifactsRoi ) Oli Artifacts are already filler here
	//adipMaskImage.unlock()
	//adipMaskImage.changes = false
	//adipMaskImage.close()
	
	 //this.adipMaskImage = adipMaskImage2.duplicate()
	 adipMaskImage2.hide()
	 adipMaskImage.hide()
	//IJ.run( adipMaskImage2, "Invert", "")
	IJ.log("Getting ADIP Mask")
	this.adipRoi = getRoiFromMask( adipMaskImage2 )
	//this.adipRoi = RoiEnlarger.enlarge( this.adipRoi, -1 )
	
	
	//adipMaskImage2.setRoi( this.adipRoi )
        //adipMaskImage2.show()
	
	// STEP  3.1: Same as STEP 2.1, but preventing overlap of IMV with adipocytes
	this.ic.run("Subtract", this.imvMaskImage, adipMaskImage2 )
        //this.imvMaskImage.show()
        // REDO SELECTION
        this.imvRoi = getRoiFromMask( this.imvMaskImage )
	
        // STEP  3.2: Same as STEP 3.1, but preventing overlap of Hematopoietic cells with adipocytes
	this.ic.run("Subtract", this.hematoMaskImage, adipMaskImage2 )
//	this.hematoMaskImage.show()
        // REDO SELECTION
        this.hematoRoi = getRoiFromMask( this.hematoMaskImage )
        
        this.adipMaskImage =  adipMaskImage2.duplicate()
        //this.adipMaskImage.show()
    }
    
    public sendResultsToQuPath() {
        // QuPath Classes and Colors for IMV (interstitium & microvasculature), Adipocytes, HematoCells and Bone
        def imv_class    = getPathClass( 'IMV')
        def adip_class   = getPathClass( 'Adipocytes')
        def aadip_class   = getPathClass( 'Adipocyte')
        
        def bone_class   = getPathClass( 'Bone' )
        def hemato_class = getPathClass( 'HematoCells' )
        def artifact_class = getPathClass( 'Artifact' )
        def bg_class = getPathClass( 'BG' )
        def tissue_class = getPathClass( 'Tissue Boundaries' )
        
        
        // Colors definition
        def imv_color    = getColorRGB(255, 58, 163)
        def adip_color   = getColorRGB(255,240,60)
        def bone_color   = getColorRGB(158, 237, 208)
        def hemato_color = getColorRGB(25, 14, 145)
        def artifact_color = getColorRGB(0,0,0)
        def bg_color = getColorRGB(255,125,50)
        def tissue_color = getColorRGB(75,220,145)
        
        // This part handles writing the results to QuPath
        // We should have new child objects
        
        
        IJ.log( "IMV: "+this.imvRoi)
        IJ.log( "Hemato: "+this.hematoRoi )
        IJ.log( "Bone: "+this.boneRoi )
        IJ.log( "Adip "+this.adipRoi )
        IJ.log( "Artifact: "+this.artifactsRoi )

        
        def imv    = getQuPathPathObject(  this.imvRoi, imv_class )
        def hemato = getQuPathPathObject(  this.hematoRoi, hemato_class )
        def bone   = getQuPathPathObject(  this.boneRoi, bone_class )
        def adip   = getQuPathPathObject(  this.adipRoi, adip_class ) 
        def artifacts   = getQuPathPathObject(  this.artifactsRoi, artifact_class )
        
        this.tissueAnnotation.addPathObject( imv )
        this.tissueAnnotation.addPathObject( hemato )
        this.tissueAnnotation.addPathObject( bone )
        this.tissueAnnotation.addPathObject( adip )
   
        def area_tissue = this.tissueAnnotation.getROI().getArea()
    
        def area_artifacts = artifacts.getROI().getArea()
  
        // Get everything calculated by MarrowQuant, all measurements in pixels
    
        // Adipocyte Area
        def area_adips = adip.getROI().getArea()
        def n_adips = 0 //??
		
        // Hemato Cells Area
        def area_hemato = hemato.getROI().getArea()
        
        // Bone Area
        def area_bone = bone.getROI().getArea()
        
        // IMV (interstitium & microvasculature) Area
        def area_imv = imv.getROI().getArea()
        
        // Area assigned to any of the other compartments
        def area_unassigned = area_tissue - area_bone - area_artifacts - area_hemato - area_adips - area_imv
        
        def denominator1 = area_tissue - area_bone - area_artifacts
    	
        def denominator2 = area_adips + area_hemato
    
        // Computing the values need to calculate the outputs, that is the cellularity, adiposity, etc.
        def cell1           = 100 * ( area_hemato / denominator1 )
        def adipo           = 100 * ( area_adips / denominator1 )
        def IMVratio        = 100 * ( area_imv / denominator1 )
        def Unassigned      = 100 * ( area_unassigned / denominator1 )
        def cell2           = 100 * ( area_hemato / denominator2 )
    
        this.tissueAnnotation.getMeasurementList().clear();
	
        // Add measurements to each Tissue region
        def measurements = this.tissueAnnotation.getMeasurementList()
        
        def U = "um"
        def px_size = Utils.getPixelSize()
        measurements.putMeasurement("Tissue Number", this.tissueNumber)
        measurements.putMeasurement("Area Hematopoietic ["+U+"^2]", area_hemato * px_size * px_size)
        measurements.putMeasurement("Area Adipocytes ["+U+"^2]", area_adips * px_size * px_size )
        measurements.putMeasurement("Area IMV (interstitium and microvasculature) ["+U+"^2]", area_imv * px_size * px_size )
        measurements.putMeasurement("Area Bone ["+U+"^2]",  area_bone * px_size * px_size )
        measurements.putMeasurement("Area of Total Tissue ["+U+"^2]", area_tissue * px_size * px_size )
        measurements.putMeasurement("Area Of Artifacts ["+U+"^2]", area_artifacts * px_size * px_size)
        measurements.putMeasurement("Area Unassigned ["+U+"^2]", area_unassigned * px_size * px_size)	
        measurements.putMeasurement("% Cellularity (Hematopoietic area/(Marrow area))", cell1)
        measurements.putMeasurement("% Adiposity (Adipocytic area/(Marrow area))", adipo)
        measurements.putMeasurement("% IMV area (Interstitium & microvasculature/(Marrow area))", IMVratio)
        measurements.putMeasurement("% Unassigned area(Unassigned/(Marrow area))", Unassigned)
        measurements.putMeasurement("% Ratio hemato/(hemato+adipo)", cell2)
        measurements.putMeasurement("Min Size", adipMin)
        measurements.putMeasurement("Max Size", adipMax)
        measurements.putMeasurement("Min Circularity", minCir)
    
        // Setting of the colors to all the annotations and detections
        imv_class.setColor( imv_color )
        adip_class.setColor( adip_color )   
        bone_class.setColor( bone_color )  
        hemato_class.setColor( hemato_color ) 
        artifact_class.setColor( artifact_color ) 
        bg_class.setColor( bg_color ) 
        tissue_class.setColor( tissue_color )
        aadip_class.setColor( adip_color )
        
        // Oli update 03.02.2020: Get Adipocytes as individual ROIs / Detections
        if ( this.getIndividualAdipocytes == true ) {
            def rm = RoiManager.getRoiManager()
            rm.reset()
            // Run Analyze Particles on the adip mask
            //IJ.run( this.adipMaskImage, "Watershed", "")
            //this.adipMaskImage.getProcessor().setAutoThreshold("Huang dark")
            IJ.run( this.adipMaskImage, "Analyze Particles...", "add")
            // Add each ROI as a child of the tissue
            def rois = rm.getRoisAsArray() as List
            
            rois.eachWithIndex{ roi, idx -> 
                this.tissueAnnotation.addPathObject( getQuPathPathObject( roi, aadip_class ) )
            }
                    measurements.putMeasurement( "Total Adipocytes", rois.size())
        }
        
        fireHierarchyUpdate()
    
    }   
    
    public PathObject getQuPathPathObject( Roi roi, def theClass ) {
        def cal = this.image.getCalibration()
        def qROI = ROIConverterIJ.convertToPathROI( roi, cal, this.downsample, -1, 0, 0 )
        //qROI = ShapeSimplifierAwt.simplifyShape( qROI, this.downsample )
        def det = new PathDetectionObject( qROI, theClass )
        det.getMeasurementList().putMeasurement("Area "+Utils.um+"^2", roi.getStatistics().area *  cal.pixelWidth * cal.pixelWidth)
        return det  
    } 
    public Roi getIJRoi( PathObject object )  {
        return ROIConverterIJ.convertToIJRoi( object.getROI(), this.image.getCalibration(), this.downsample )
    } 
    
    // Use Color Deconvolution Plugin in MarrowQuant
    public ImagePlus[] colorDeconvolution ( ImagePlus image, String stain ) {
            def cd = new Colour_Deconvolution()
            def matList = cd.getStainList()
            def mt = matList.get( stain )
            def stackList = mt.compute( false, true, image )
            // This returns an array of ImageStacks
            
            // Make into an ImagePlus            
            def imageStack = new ImageStack( stackList[0].getWidth(), stackList[0].getHeight() )
            
            stackList.each { imageStack.addSlice( it.getProcessor( 1 ) ) }
            
            def deconvolved = stackList.collect{ new ImagePlus( image.getTitle()+"-"+stain, it ) }
            
            return deconvolved
            
    }
    
    public Roi getRoiFromMask( ImagePlus image ) {
        def proc = image.getProcessor()
        
        proc.setThreshold( 127, 255, ImageProcessor.NO_LUT_UPDATE )
        return new ThresholdToSelection().convert( proc )
        
    }
    
    // Reimplemented Color Balance Class
    public ImagePlus colorBalance( ImagePlus image, Roi roi ) {

        // Make sure it is RGB
        if ( image.getType() != ImagePlus.COLOR_RGB ) return null 
       
        //Remove ROI before duplication
        image.killRoi()
        
        
        def image_balanced = image.duplicate()
        image_balanced.setTitle( "Color Balanced "+ image.getTitle() )
        
        // Make a 3 slice stack
        def ic = new ImageConverter( image_balanced )
        ic.convertToRGBStack()
        image_balanced.setRoi( roi )
        
        def statOptions = Measurements.MEAN+Measurements.MEDIAN
        
        // Calculate mean/median of each color
        image_balanced.setPosition(1) //R
        def isR = image_balanced.getStatistics(statOptions)
        
        image_balanced.setPosition(2) //G
        def isG = image_balanced.getStatistics(statOptions)
        
        image_balanced.setPosition(3) //B
        def isB = image_balanced.getStatistics(statOptions)
        
        
        def rgb = [isR.mean,isG.mean,isB.mean]
        
        // find largest value.
        double maxVal = 0;
        int idx = -1;
        double scale;
        for( def i=0; i<3;i++) {
            if (rgb[i] > maxVal) {
                idx = i;
        	maxVal = rgb[i];
        	scale = 255/maxVal;
            }
        }
        
        // Remove ROI again to make sure we apply the multiplication to the whole image
        image_balanced.killRoi();
        
        rgb.eachWithIndex { val, i ->
            image_balanced.setPosition(i+1);
            def ip = image_balanced.getProcessor();
            //def normVal = maxVal/rgb[i]*scale;
            //IJ.log(""+val+", "+rgb[i]+", "+maxVal);
            ip.multiply(maxVal/rgb[i]*scale); //Scaling the other channels to the largest one.
        }
        
        // Convert it back
        def ic2 = new ImageConverter( image_balanced )
        ic2.convertRGBStackToRGB()
        return image_balanced
    }
}

// The lines below allow us to user the Builder Pattern withourh having to declare it in the MarrowQuant class
@Builder(builderStrategy = ExternalStrategy, forClass = MarrowQuant, prefix = 'assign', buildMethodName = 'create')
class MQBuilder {
    MQBuilder() {
        downsample       = 4
        adipMin 	 = 5
        adipMax 	 = 1000000000
        minCir           = 0.3
        excludeOnEdges   = false
        testAP 	         = false
        fixNDPIBug       = false
        testParams       = false
        getIndividualAdipocytes = true
    }
}


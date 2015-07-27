package nhs.genetics.cardiff;

import com.google.gson.Gson;

import java.io.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Main {

    private static final double version = 0.3;
    private static final Logger log = Logger.getLogger(Main.class.getName());

    public static void main(String[] args) {

        if (args.length != 3) {
            System.err.println("Usage: <Chrom> <Start> <Stop>");
            System.err.println("Coordinates should be 0-based");
            System.exit(1);
        }

        log.log(Level.INFO, "Primer designer v" + version);
        if (Configuration.isDebug()) log.log(Level.INFO, "Debugging mode");

        StringBuilder bedOutput = new StringBuilder();
        Output output = new Output();
        Gson gson = new Gson();
        GenomicLocation suppliedROI = new GenomicLocation(args[0], Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        HashSet<GenomicLocation> overlappingExonicRegionsOfInterest = new HashSet<>();
        HashSet<GenomicLocation> mergedOverlappingExonicRegionsOfInterest = new HashSet<>();
        ArrayList<GenomicLocation> splitFinalRegionsOfInterest = new ArrayList<>();

        log.log(Level.INFO, "Designing primer pair to cover supplied region of interest " + suppliedROI.getChromosome() + ":" + suppliedROI.getStartPosition() + "-" + suppliedROI.getEndPosition());

        //find overlapping exons with ROI
        for (String feature : BedtoolsWrapper.getOverlappingFeatures(Configuration.getBedtoolsFilePath(), Configuration.getExonsBed(), suppliedROI)){

            String[] fields = feature.split("\t");
            overlappingExonicRegionsOfInterest.add(new GenomicLocation(fields[3], Integer.parseInt(fields[4]), Integer.parseInt(fields[5])));

            log.log(Level.INFO, "Target overlaps with exon " + fields[3] + ":" + fields[4] + "-" + fields[5]);
        }

        //loop over exonic overlaps and merge
        if (overlappingExonicRegionsOfInterest.size() > 0){

            //merge exonic overlaps
            for (GenomicLocation mergedOverlappingExonicROI : BedtoolsWrapper.mergeOverlappingFeatures(Configuration.getBedtoolsFilePath(), overlappingExonicRegionsOfInterest)) {

                mergedOverlappingExonicRegionsOfInterest.add(mergedOverlappingExonicROI);

                log.log(Level.INFO, "Exonic target(s) were merged into " + mergedOverlappingExonicROI.getChromosome() + ":" + mergedOverlappingExonicROI.getStartPosition() + "-" + mergedOverlappingExonicROI.getEndPosition());
            }

        } else {
            log.log(Level.INFO, "Target does not overlap with any supplied exons");
            mergedOverlappingExonicRegionsOfInterest.add(suppliedROI); //could not find overlapping exons
        }

        //loop over final ROIs and split into amplifible amplicons
        for (GenomicLocation finalROI : mergedOverlappingExonicRegionsOfInterest){

            /*split target
            if ((finalROI.getEndPosition() - finalROI.getStartPosition()) + 1 > Configuration.getMaxTargetLength()){

                int numberOfWindows = (((finalROI.getEndPosition() - finalROI.getStartPosition()) + 1) / Configuration.getMaxTargetLength()) + 1;

                log.log(Level.WARNING, "Target " + finalROI.getChromosome() + ":" + finalROI.getStartPosition() + "-" + finalROI.getEndPosition() + " exceeds max target length. Splitting into " + numberOfWindows + " fragments");

                for (String line : BedtoolsWrapper.splitRegionIntoWindows(Configuration.getBedtoolsFilePath(), finalROI, numberOfWindows)){
                    String[] fields = line.split("\t");
                    splitFinalRegionsOfInterest.add(new GenomicLocation(fields[0], Integer.parseInt(fields[1]), Integer.parseInt(fields[2])));
                }

                splitFinalRegionsOfInterest.add(suppliedROI);

            } else {
                splitFinalRegionsOfInterest.add(finalROI);
            }*/

            splitFinalRegionsOfInterest.add(finalROI);
        }

        //exonic and split ROIs
        for (GenomicLocation finalROI : splitFinalRegionsOfInterest) {

            //convert to 1-based
            finalROI.convertTo1Based();

            log.log(Level.INFO, "Designing amplicon for target " + finalROI.getChromosome() + ":" + finalROI.getStartPosition() + "-" + finalROI.getEndPosition());

            //get sequence
            ReferenceSequence sequence = new ReferenceSequence(finalROI, Configuration.getReferenceGenomeFasta(), new File(Configuration.getReferenceGenomeFasta() + ".fai"), Configuration.getPadding());
            sequence.populateReferenceSequence();

            if (sequence.isRefAllNSites()) {
                log.log(Level.WARNING, "Could not design primer for target containing all N-sites: " + finalROI.getChromosome() + ":" + finalROI.getStartPosition() + "-" + finalROI.getEndPosition());
                break;
            }

            //design primers
            Primer3 primer3 = new Primer3(
                    sequence,
                    finalROI,
                    Configuration.getPadding(),
                    Configuration.getMaxPrimerDistance(),
                    Configuration.getPrimer3FilePath(),
                    Configuration.getPrimerMisprimingLibrary(),
                    Configuration.getPrimer3Settings(),
                    Configuration.getPrimerThermodynamicPararmetersPath()
            );
            primer3.setExcludedRegions(Configuration.getExcludedVariants(), Configuration.getMaxIndelLength());
            primer3.callPrimer3();

            if (Configuration.isDebug()){
                try (PrintWriter p = new PrintWriter(finalROI.getChromosome() + "_" + finalROI.getStartPosition() + "_" + finalROI.getEndPosition() + "_primer3out.txt")) {
                    for (String line : primer3.getPrimer3Output()) {
                        p.println(line);
                    }
                    p.close();
                } catch (IOException e) {
                    log.log(Level.SEVERE, e.getMessage());
                }
            }

            if (!Configuration.isDebug()){

                primer3.splitPrimer3Output();
                primer3.checkPrimerAlignments();

                suppliedROI.convertTo1Based();

                for (PrimerPair primerPair : primer3.getFilteredPrimerPairs()){

                    //print primers to JSON
                    output.setChromosome(primerPair.getAmplifiableRegion().getChromosome());
                    output.setStartPosition(primerPair.getAmplifiableRegion().getStartPosition());
                    output.setEndPosition(primerPair.getAmplifiableRegion().getEndPosition());
                    output.setLeftSequence(primerPair.getLeftSequence());
                    output.setRightSequence(primerPair.getRightSequence());
                    output.setLeftTm(primerPair.getLeftTm());
                    output.setRightTm(primerPair.getRightTm());

                    //print primers to bed
                    bedOutput.append(suppliedROI.getChromosome() + "\t");
                    bedOutput.append((primerPair.getAmplifiableRegion().getStartPosition() - primerPair.getLeftSequence().length()) - 1 + "\t");
                    bedOutput.append((primerPair.getAmplifiableRegion().getEndPosition() + primerPair.getRightSequence().length()) + "\t");
                    bedOutput.append("\t");
                    bedOutput.append(Math.round(primerPair.getPairPenalty()) + "\t");
                    if (primerPair.getAmplifiableRegion().getStrand() == 1) bedOutput.append("+\t"); else bedOutput.append("-\t");
                    bedOutput.append((primerPair.getAmplifiableRegion().getStartPosition() - 1) + "\t");
                    bedOutput.append(primerPair.getAmplifiableRegion().getEndPosition());
                    bedOutput.append("\n");

                }

            }

        }

        if (!Configuration.isDebug()){
            /*ReferenceSequence paddedSequence = new ReferenceSequence(suppliedROI, Configuration.getReferenceGenomeFasta(), new File(Configuration.getReferenceGenomeFasta() + ".fai"), Configuration.getPadding());
            paddedSequence.populateReferenceSequence();

            MutationSurveyorReference mutationSurveyorReference = new MutationSurveyorReference(suppliedROI, paddedSequence, Configuration.getPadding());
            mutationSurveyorReference.writeMutationSurveyorSeqFile();*/
        }

        if (!Configuration.isDebug()){

            //write output to stdout
            System.out.print(gson.toJson(output));

            /*print primer pairs to BED
            try (PrintWriter p = new PrintWriter("Primers.bed")){
                p.print(bedOutput.toString());
                p.close();
            } catch (IOException e){
                log.log(Level.SEVERE, e.toString());
            }*/

        }

    }

}

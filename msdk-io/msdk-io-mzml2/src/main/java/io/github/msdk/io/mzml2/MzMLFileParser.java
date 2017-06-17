/*
 * (C) Copyright 2015-2016 by MSDK Development Team
 *
 * This software is dual-licensed under either
 *
 * (a) the terms of the GNU Lesser General Public License version 2.1 as published by the Free
 * Software Foundation
 *
 * or (per the licensee's choosing)
 *
 * (b) the terms of the Eclipse Public License v1.0 as published by the Eclipse Foundation.
 */

package io.github.msdk.io.mzml2;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nonnull;
import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.Attribute;
import javax.xml.stream.events.EndElement;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;

import io.github.msdk.MSDKException;
import io.github.msdk.MSDKMethod;
import io.github.msdk.datamodel.chromatograms.Chromatogram;
import io.github.msdk.datamodel.rawdata.MsFunction;
import io.github.msdk.datamodel.rawdata.MsScan;
import io.github.msdk.datamodel.rawdata.RawDataFile;
import it.unimi.dsi.io.ByteBufferInputStream;

/**
 * <p>MzMLFileParser class.</p>
 *
 * @author plusik
 * @version $Id: $Id
 */
public class MzMLFileParser implements MSDKMethod<RawDataFile> {
  private final @Nonnull File mzMLFile;
  private final @Nonnull ArrayList<MsScan> spectrumList;
  private RawDataFile newRawFile;
  private Integer lastScanNumber = 0;
  private boolean canceled = false;

  /**
   * <p>Constructor for MzMLFileParser.</p>
   *
   * @param mzMLFilePath a {@link java.lang.String} object.
   */
  public MzMLFileParser(String mzMLFilePath) {
    this(new File(mzMLFilePath));
  }

  /**
   * <p>Constructor for MzMLFileParser.</p>
   *
   * @param mzMLFilePath a {@link java.nio.file.Path} object.
   */
  public MzMLFileParser(Path mzMLFilePath) {
    this(mzMLFilePath.toFile());
  }

  /**
   * <p>Constructor for MzMLFileParser.</p>
   *
   * @param mzMLFile a {@link java.io.File} object.
   */
  public MzMLFileParser(File mzMLFile) {
    this.mzMLFile = mzMLFile;
    this.spectrumList = new ArrayList<>();
  }

  /**
   * <p>execute.</p>
   *
   * @return a {@link io.github.msdk.datamodel.rawdata.RawDataFile} object.
   * @throws io.github.msdk.MSDKException if any.
   */
  public RawDataFile execute() throws MSDKException {

    try {
      MzMLFileMemoryMapper mapper = new MzMLFileMemoryMapper();
      ByteBufferInputStream is = mapper.mapToMemory(mzMLFile);

      List<Chromatogram> chromatogramsList = new ArrayList<>();
      List<MsFunction> msFunctionsList = new ArrayList<>();

      // Create the MzMLRawDataFile object
      final MzMLRawDataFile newRawFile =
          new MzMLRawDataFile(mzMLFile, null, msFunctionsList, spectrumList, chromatogramsList);
      this.newRawFile = newRawFile;

      XMLInputFactory xmlInputFactory = XMLInputFactory.newInstance();
      XMLEventReader xmlEventReader = xmlInputFactory.createXMLEventReader(is);

      boolean insideSpectrumListFlag = false;
      boolean insideBinaryDataArrayFlag = false;
      int defaultArrayLength = 0;
      MzMLSpectrum spectrum = null;
      MzMLBinaryDataInfo binaryDataInfo = null;
      while (xmlEventReader.hasNext()) {
        if (canceled)
          return null;

        XMLEvent xmlEvent = xmlEventReader.nextEvent();
        if (xmlEvent.isStartElement()) {
          StartElement startElement = xmlEvent.asStartElement();

          if (startElement.getName().getLocalPart().equals("spectrumList")
              && xmlEventReader.hasNext()) {
            xmlEvent = xmlEventReader.nextEvent();
            insideSpectrumListFlag = true;
          }

          if (insideSpectrumListFlag && xmlEventReader.hasNext()) {
            switch (startElement.getName().getLocalPart()) {
              case "spectrum":
                xmlEvent = xmlEventReader.nextEvent();
                System.out.println("---");
                if (spectrum != null)
                  spectrumList.add(spectrum);
                spectrum = new MzMLSpectrum(newRawFile);
                Attribute arrayLengthAttr =
                    startElement.getAttributeByName(new QName("defaultArrayLength"));
                Attribute idAttr = startElement.getAttributeByName(new QName("id"));
                defaultArrayLength = Integer.valueOf(arrayLengthAttr.getValue());
                spectrum.setId(idAttr.getValue());
                spectrum.setScanNumber(getScanNumber(idAttr.getValue()));
                spectrum.setByteBufferInputStream(is);
                break;
              case "binaryDataArray":
                xmlEvent = xmlEventReader.nextEvent();
                insideBinaryDataArrayFlag = true;
                binaryDataInfo = new MzMLBinaryDataInfo();
                Attribute encodedLengthAttr =
                    startElement.getAttributeByName(new QName("encodedLength"));
                binaryDataInfo.setEncodedLength(Integer.valueOf(encodedLengthAttr.getValue()));
                Attribute arrayLengthAttr2 =
                    startElement.getAttributeByName(new QName("arrayLength"));
                if (arrayLengthAttr2 != null) {
                  defaultArrayLength = Integer.valueOf(arrayLengthAttr2.getValue());
                }
                binaryDataInfo.setArrayLength(defaultArrayLength);
                break;
              case "cvParam":
                if (!insideBinaryDataArrayFlag && spectrum != null) {
                  xmlEvent = xmlEventReader.nextEvent();
                  Attribute accessionAttr = startElement.getAttributeByName(new QName("accession"));
                  Attribute valueAttr = startElement.getAttributeByName(new QName("value"));
                  Attribute unitAccessionAttr =
                      startElement.getAttributeByName(new QName("unitAccession"));
                  MzMLCVParam cvParam =
                      new MzMLCVParam(accessionAttr.getValue(), valueAttr.getValue(), null);
                  if (unitAccessionAttr != null)
                    cvParam.setUnitAccession(unitAccessionAttr.getValue());
                  spectrum.getCVParams().add(cvParam);
                }
                break;
              case "binary":
                if (spectrum != null) {
                  while (xmlEventReader.hasNext()) {
                    xmlEvent = xmlEventReader.nextEvent();
                    if (xmlEvent.isCharacters()) {
                      binaryDataInfo.setPosition(xmlEvent.getLocation().getCharacterOffset());
                      break;
                    }
                  }
                }
                break;
            }
          }

          if (insideBinaryDataArrayFlag && startElement.getName().getLocalPart().equals("cvParam")
              && binaryDataInfo != null && xmlEventReader.hasNext()) {
            xmlEvent = xmlEventReader.nextEvent();
            Attribute accessionAttr = startElement.getAttributeByName(new QName("accession"));
            if (binaryDataInfo.isBitLengthAccession(accessionAttr.getValue())) {
              binaryDataInfo.setBitLength(accessionAttr.getValue());
            } else if (binaryDataInfo.isCompressionTypeAccession(accessionAttr.getValue())) {
              binaryDataInfo.setCompressionType(accessionAttr.getValue());
            } else if (binaryDataInfo.isArrayTypeAccession(accessionAttr.getValue())) {
              binaryDataInfo.setArrayType(accessionAttr.getValue());
            } else {
              break; // A better approach to skip UV Scans would
                     // be to only break accession which define
                     // the array type and isn't either m/z or
                     // intensity values. We would have to list
                     // out all array types in that case.
            }
          }

        }

        if (xmlEvent.isEndElement()) {
          EndElement endElement = xmlEvent.asEndElement();
          if (endElement.getName().getLocalPart().equals("spectrumList")
              && xmlEventReader.hasNext()) {
            xmlEvent = xmlEventReader.nextEvent();
            insideSpectrumListFlag = false;
          }
        }

        if (insideSpectrumListFlag && xmlEvent.isEndElement()) {
          EndElement endElement = xmlEvent.asEndElement();
          if (endElement.getName().getLocalPart().equals("binaryDataArray")
              && xmlEventReader.hasNext()) {
            xmlEvent = xmlEventReader.nextEvent();
            if (binaryDataInfo.getArrayType().getValue().equals("MS:1000514"))
              spectrum.setMzBinaryDataInfo(binaryDataInfo);
            if (binaryDataInfo.getArrayType().getValue().equals("MS:1000515"))
              spectrum.setIntensityBinaryDataInfo(binaryDataInfo);

            insideBinaryDataArrayFlag = false;
          }
        }

      }
    } catch (IOException e) {
      throw (new MSDKException(e));
    } catch (XMLStreamException e) {
      throw (new MSDKException(e));
    }

    return newRawFile;
  }

  /**
   * <p>Getter for the field <code>spectrumList</code>.</p>
   *
   * @return a {@link java.util.ArrayList} object.
   */
  public ArrayList<MsScan> getSpectrumList() {
    return spectrumList;
  }

  /**
   * <p>getScanNumber.</p>
   *
   * @param spectrumId a {@link java.lang.String} object.
   * @return a {@link java.lang.Integer} object.
   */
  public Integer getScanNumber(String spectrumId) {
    final Pattern pattern = Pattern.compile("scan=([0-9]+)");
    final Matcher matcher = pattern.matcher(spectrumId);
    boolean scanNumberFound = matcher.find();

    // Some vendors include scan=XX in the ID, some don't, such as
    // mzML converted from WIFF files. See the definition of nativeID in
    // http://psidev.cvs.sourceforge.net/viewvc/psidev/psi/psi-ms/mzML/controlledVocabulary/psi-ms.obo
    if (scanNumberFound) {
      Integer scanNumber = Integer.parseInt(matcher.group(1));
      lastScanNumber = scanNumber;
      return scanNumber;
    }

    Integer scanNumber = lastScanNumber + 1;
    lastScanNumber++;
    return scanNumber;
  }

  /** {@inheritDoc} */
  @Override
  public Float getFinishedPercentage() {
    // TODO Auto-generated method stub
    return null;
  }

  /** {@inheritDoc} */
  @Override
  public RawDataFile getResult() {
    return newRawFile;
  }

  /** {@inheritDoc} */
  @Override
  public void cancel() {
    this.canceled = true;
  }
}

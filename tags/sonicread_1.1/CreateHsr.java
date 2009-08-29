/*
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 *  Remco den Breeje, <stacium@gmail.com>
 */

import java.io.FileReader;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Scanner;
import java.util.Locale;

class CreateHsr {
  public int ix;	/* byte index */
  public int is;	/* Section index */ 
  public int iss;	/* Section size index */
  public int section_start;
  public int section_number;
  public int total_byte_cnt;
  public int[] data;
  private int crc;

  public CreateHsr()
  {
    data = new int[8*1024];
    crc = ix = is = iss = 0;
    section_start = 8;
    section_number = 1;
    total_byte_cnt = 0;
  }

  public boolean IsDone()
  {
    return(total_byte_cnt > 0 && (ix == total_byte_cnt));
  }

  public boolean AddData(int b)
  {
    if(b >= 0)
    {
      data[ix++] = b;
      crc16(data[ix - 1]);
      if(ix == 1 && data[0] != 85)
      {
	System.out.println("Bad first section header (byte 1)");
	return false;
      }
      if(ix == 2 && data[1] == 81) 
	System.out.println("Found S510");
      if(ix == 3 && data[2] != 1)
      {
	System.out.println("Bad first section header (byte 3)");
	return false;
      }
      /* looking for end of section header */
      if(ix == 8)
      {
	if(crc > 0)
	{
	  System.out.println("First section CRC error");
	  return false;
	}

	/* calculate total number of bytes to be received (8=section header) */
	total_byte_cnt = 8 + 5 * data[3] + data[4] + 256 * data[5] + 1;
      }
      if(ix <= 8) /* got all info we need for the first 8 bytes */
	return true;
      if(ix == section_start + 3) /* new section (+3=header size) */
      {
	if(data[section_start] != 85 ||
	    data[section_start + 1 ] != section_number ||
	    data[section_start + 2 ] < 1 || 
	    data[section_start + 2 ] > 60)
	{
	  System.out.format("Bad section header, section_number: %d\n", section_number);
	}
	if(section_number == 1) { /* tell user, we started listening */
	  System.out.format("Section %d started at %d\n", section_number, ix);
	}
      }
      /* looking for end of section */
      if(ix >= section_start + 3 && ix == section_start + data[section_start + 2] + 3 + 2)
      {
	System.out.format("Section %d ended at %d\n", section_number, ix);
	if(crc > 0)
	{
	  System.out.println("Section CRC error");
	  return false;
	}
	/* update section start with new index */
	section_start = ix;
	section_number++;
      }
      /* looking for final byte */
      if(ix == total_byte_cnt)
      {
	if(data[ix - 1] != 7)
	{
	  System.out.println("Bad trailer byte");
	  return false;
	}
	if(section_start != ix - 1 || section_number != data[3] + 1)
	{
	  System.out.println("Bad section structure");
	  return false;
	}
      }
      
      // progress
      // System.out.format("%d of %d\n", ix, total_byte_cnt);
    }
    return true;
  }

  public void WriteHsr() throws Exception
  {
    int i;
    try { 
      // todo, make filename dynamic dynamic
      DataOutputStream os = new DataOutputStream(new FileOutputStream("exercise.hsr"));
      os.writeByte((byte)(total_byte_cnt + 2));
      os.writeByte((byte)((total_byte_cnt + 2) / 255));
      for(i = 0;i < total_byte_cnt;i++)
      {
	os.writeByte(data[i]);
      }
      os.close();
    }
    catch (Exception e) {
      throw new Exception ("Error writing hsr");
    }
  }

  public void WriteSrd() throws Exception
  {
    int ii = 0;
    int sectionIx = 0;
    int sectionsInData = 0;
    int srdBytes = 0;
    int[] srd = new int[1];

    while(ii < total_byte_cnt)
    {
      // get first section
      if(sectionIx == 0)
      {
	// get first section
	if(data[0] == 85)
	{
	  sectionsInData = data[3];
	  
	  // alloc mem
	  srdBytes = data[5] * 0x100 + data[4] + 2;
	  srd = new int[srdBytes];
      
	  // set size
	  srd[0] = (int)(byte)srdBytes;
	  srd[1] = (int)(byte)(srdBytes / 255);
	  srdBytes = 2;

	  // ok, first section read, continue
	  sectionIx++;
	  ii += 8;
	  continue;
	}
	else
	{
	  throw new Exception ("The exercise data is not valid, the first section could not be found");
	}
      }
      else
      {
	// find new section
	if(data[ii] == 85)
	{
	  // check section number
	  if(data[ii + 1] != sectionIx)
	  {
	    throw new Exception ("Wrong section index");   
	  }

	  // get size of this section
	  int sectionLength = data[ii + 2];
	  
	  System.out.format(">>> new section #%d(%d) found at %d with %d bytes\n", 
	      sectionIx, sectionsInData, ii, sectionLength);


	  // set data in sections array (s) 
	  System.arraycopy(data, ii + 3, srd, srdBytes, sectionLength);
	  srdBytes += sectionLength;
	  System.out.format("Got %d bytes\n", srdBytes);

	  // ok, section read, continue
	  sectionIx++;
	  ii += data[ii + 2] + 5; // +5 -> section header length
	  continue;
	}
      }

      // done. check for no-more-sections byte at the end of the file
      if((sectionIx - 1) != sectionsInData)
      {
	throw new Exception ("Could not find all sections");
      }
      if(data[ii] != 7)
      {
	throw new Exception ("Could not find no-more-sections byte");
      }
      // ok, all set
      break;
    }

    System.out.format("Writing %d bytes\n", srdBytes);

    try { 
      // todo, make filename dynamic dynamic
      DataOutputStream os = new DataOutputStream(new FileOutputStream("exercise.srd"));
      for(ii = 0;ii < srdBytes;ii++)
      {
	os.writeByte((byte)srd[ii]);
      }
      os.close();
    }
    catch (Exception e) {
      System.out.println("Error writing srd");
      e.printStackTrace();
    }
  }

  public int crc16(int data)
  {
    int i;
    for(i=7; i >= 0; i--)
    {
      crc <<= 1;
      if((data & (1 << i)) > 0)
	crc ^= 1;
      if((crc & 0x10000) > 0)
	crc ^= 0x18005;
    }
    return crc;
  }
}


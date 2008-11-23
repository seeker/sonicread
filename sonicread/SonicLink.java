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
import java.io.IOException;
import java.util.Scanner;
import java.util.Locale;

class SonicLink {
  private int t, t_active, t_byte, n_byte, n_bytes;
  private int[] c = new int[6];
  private double[] filter_input_signal = new double[64];
  private double[] dilate_input_signal = new double[8];
  private double[] median_input_signal = new double[8];
  private double[][] b_median_input_signal = new double[8][8];
  private double[] decision_input_signal = new double[64];
  private double[] decision_amplitude = new double[64];
  private double[] decision_threshold_levels = { 0.25,0.30,0.35,0.40,0.45 };
  private int filter_pos, dilate_pos, median_pos, b_median_pos, decision_pos;
  private int decision_count_down;
  private int[] decisions = new int[7];
  private double level_value;
  private int level_c;

  public SonicLink()
  {
    restart();
  }

  public void restart()
  {
    t = t_active = 0;
    filter_pos = dilate_pos = median_pos = b_median_pos = decision_pos = -1;
    level_value = 0;
    level_c = 0;
  }
    
  /* process next sample */
  public int decode(double x)
  {
    int i,j,k;
    //System.out.format(".");
    t++;
    //System.out.format("decode input: %e\n", x);
    x = filter(x);
    //System.out.format("filter() out: %e\n", x);
    x = dilate(x);
    //System.out.format("dilate() out: %e\n", x);
    x = median(x);
    //System.out.format("median() out: %e\n", x);
    make_decision(x);
    //System.out.format("make_decision() out: %d %d %d %d\n", decisions[0], decisions[1], decisions[2], decisions[3]);
    b_median();
    //System.out.format("b_median() out: %d %d %d %d\n", decisions[0], decisions[1], decisions[2], decisions[3]);
    update_level(x);

    /* activation test */
    if((decisions[6] > 0) && (t_active == 0))
    {
      t_active = t;
      t_byte = 0;
      n_bytes = 0;
      System.out.format("Processing signal at %d\n", t);
    }
    if(t_active == 0)
          return -1;
    /* deactivation test after a long period of inactivity */
    if(t > t_active + 10000)
    {
      //update_status("byte start time out",1);
      System.out.format("Byte start timed out\n");
      return -2;
    }

    /* new byte? */
    if(t_byte == 0 && (decisions[5] > 0))
    { 
      t_active = t_byte = t;
      n_bytes++;
      n_byte = 0;
      for(i = 0;i < 6;i++)
	c[i] = 0;
    }

    /* processing a byte? */
    if(t_byte > 0)
    {
      j = (t - t_byte) / 88;  // bit number; the duration of a bit is very close t
      if(j >= 10)
      { // end of the byte
	if((n_byte & 1) == 0)
	{ // first bit (lsb) must be a one
	  System.out.format("bad byte start\n");
	  return -2;
	}
	if(n_byte >= 512)
	{ // last bit (msb) must be a zero
	  System.out.format("bad byte finish\n");
	  return -2;
	}
	n_byte >>= 1;
	t_byte = 0;
	if(n_bytes > 5)
	  return n_byte;
	if(n_byte != 0xAA)
	{ // bad synchronization byte
	  System.out.format("bad synchronization byte\n");
	  return -2;
	}
	return -1; // discard synchronization byte
      }
      k = t - t_byte - 88 * j;  // sample number inside the bit
      if(k < 64) // the bit burst has a duration of close to 60 samples (here we u
	for(i = 0;i < 6;i++)
	  c[i] += decisions[i];
      else if(k == 64)
      {
	k = 0;
	for(i = 0;i < 6;i++)
	{
	  if(c[i] >= 30) // threshold = 60/2
	    k += (i < 5) ? 1 : 2;
	  c[i] = 0;
	}
	if(k >= 4) // majority rule
	  n_byte += 1 << j;
      }
    }
    return -1;
  }

  /*
   * Pass input signal through a (linear phase FIR) high-pass filter
   *
   * y = filter(remez(40,[0 2000 4000 22050]/22050,[0 0 1 1],[2 1]),1,x);
   */
  private double filter(double x)
  {
    double[] h = {
      0.00379802504960,-0.01015567605831,-0.00799539667861,-0.00753846964193,
      -0.00613119820747,-0.00283788804837, 0.00237999784896, 0.00894445674876,
      0.01562664364293, 0.02085836025169, 0.02288210087128, 0.02006200096880,
      0.01129559749322,-0.00374264061795,-0.02440163523552,-0.04905850773028,
      -0.07526471722031,-0.10006201874319,-0.12045502068751,-0.13386410688608,
      0.86146086608477,-0.13386410688608,-0.12045502068751,-0.10006201874319,
      -0.07526471722031,-0.04905850773028,-0.02440163523552,-0.00374264061795,
      0.01129559749322, 0.02006200096880, 0.02288210087128, 0.02085836025169,
      0.01562664364293, 0.00894445674876, 0.00237999784896,-0.00283788804837,
      -0.00613119820747,-0.00753846964193,-0.00799539667861,-0.01015567605831,
      0.00379802504960
    }; 
    double y;
    int i;

    if(filter_pos == -1)
    {
      filter_pos = 0;
      for(i = 0;i < 64;i++)
	filter_input_signal[i] = 0.0;
    }
    filter_input_signal[filter_pos] = x;
    y = 0.0;
    for(i = 0;i <= 40;i++)
      y += h[i] * filter_input_signal[(filter_pos - i) & 63];
    filter_pos = (filter_pos + 1) & 63;
    return y;
  }

  /* 
   * Dilate the rectified signal 
   *
   * y = delay(3,ordfilt2(abs(x),7,ones(1,7)));
   */
  private double dilate(double x)
  {
    double y;
    int i;

    if(dilate_pos == -1)
    {
      dilate_pos = 0;
      for(i = 0;i < 8;i++)
	dilate_input_signal[i] = 0.0;
    }
    dilate_input_signal[dilate_pos] = (x >= 0.0) ? x : -x; /* abs */
    y = dilate_input_signal[dilate_pos];
    for(i = 1;i <= 6;i++)
      if(dilate_input_signal[(dilate_pos - i) & 7] > y)
	y = dilate_input_signal[(dilate_pos - i) & 7];
    dilate_pos = (dilate_pos + 1) & 7;
    return y;
  }

  /*
   * Apply a median filter to the signal
   *
   * y = delay(2,ordfilt2(x,3,ones(1,5)));
   */
  private double median(double x)
  {
    double t;
    double[] y = new double[5];
    int i,j;

    if(median_pos == -1)
    {
      median_pos = 0;
      for(i = 0;i < 8;i++)
	median_input_signal[i] = 0.0;
    }
    median_input_signal[median_pos] = x;
    for(i = 0;i <= 4;i++)
    { // sort
      t = median_input_signal[(median_pos - i) & 7];
      for(j = 0;j < i;j++)
	if(t < y[j])
	{
	  y[i] = y[j];
	  y[j] = t;
	  t = y[i];
	}
      y[i] = t;
    }
    median_pos = (median_pos + 1) & 7;
    return y[2];
  }

  /*
   * decide if each signal sample corresponds to part of a one or not
   * y = x;
   * for k=2:length(x)
   *   y(k) = max(x(k),0.9999*y(k-1));
   * end
   * amplitude = delay(20,ordfilt2(y,41,ones(1,41)));
   * decisions = [ delay(20,x) >= level*amplitude  amplitude >= 15*delay(30,amplitude) ])
   */
  private double make_decision(double x)
  { 
    // decisions[0..4] = above/below threshold for five different threshold levels
    // decisions[5] = best above/below decision based on a majority rule
    // decisions[6] = abrupt change/no abrupt change in the amplitude (start flag)
    double t;
    int i;

    if(decision_pos == -1)
    {
      decision_pos = 0;
      decision_count_down = 64;
      for(i = 0;i < 64;i++)
      {
	decision_input_signal[i] = 0.0;
	decision_amplitude[i] = 0.0;
      }
    }
    // compute the amplitude
    decision_input_signal[decision_pos] = x;
    decision_amplitude[decision_pos] = 0.9999 * decision_amplitude[(decision_pos - 1) & 63];
    if(x > decision_amplitude[decision_pos])
      decision_amplitude[decision_pos] = x;
    // dilate the amplitude
    t = decision_amplitude[decision_pos];
    for(i = 1;i <= 40;i++)
      if(decision_amplitude[(decision_pos - i) & 63] > t)
	t = decision_amplitude[(decision_pos - i) & 63];
    // compare the (delayed) signal with the (dilated) amplitude scaled by a threshold le
    decisions[5] = 0;
    for(i = 0;i < 5;i++)
    {
      decisions[i] = (decision_input_signal[(decision_pos - 20) & 63] >= decision_threshold_levels[i] * t) ? 1 : 0;
      decisions[5] += decisions[i];
    }
    decisions[5] = (decisions[5] >= 3) ? 1 : 0; // majority rule (median)
    decisions[6] = (decision_amplitude[decision_pos] >= 15.0 * decision_amplitude[(decision_pos - 30) & 63]) ? 1 : 0;
    if(decision_count_down > 0)
    {
      --decision_count_down;
      decisions[6] = 0;
    }
    i = decision_pos;
    decision_pos = (decision_pos + 1) & 63;
    return decision_amplitude[i];
  }

  /* 
   * Apply a median filter to the decisions
   *
   * d = delay(2,ordfilt2(d,3,ones(1,5)));
   */
  private void b_median()
  {
    int i,j;

    if(b_median_pos == -1)
    {
      b_median_pos = 0;
      for(i = 0;i < 8;i++)
	for(j = 0;j < 8;j++)
	  b_median_input_signal[i][j] = 0;
    }
    for(i = 0;i < 7;i++)
    {
      b_median_input_signal[i][b_median_pos] = decisions[i];
      for(j = 1;j <= 4;j++)
	decisions[i] += b_median_input_signal[i][(b_median_pos - j) & 7];
      decisions[i] = (decisions[i] >= 3) ? 1 : 0;  // median (majority rule)
    }
    b_median_pos = (b_median_pos + 1) & 7;
  }

  private void update_level(double l)
  {
    if(l > level_value)
      level_value = l;
    if(++level_c >= 4410)
    { // update level every 0.1 seconds (sampling frequency = 44100)
      //v_level = level_value;
      level_value = 0.0;
      level_c = 0;
      //display_info();
    }
  }
}


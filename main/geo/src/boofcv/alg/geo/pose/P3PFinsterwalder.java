/*
 * Copyright (c) 2011-2012, Peter Abeles. All Rights Reserved.
 *
 * This file is part of BoofCV (http://boofcv.org).
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package boofcv.alg.geo.pose;

import boofcv.numerics.solver.PolynomialOps;
import boofcv.struct.FastQueue;
import georegression.struct.point.Point2D_F64;

import static boofcv.alg.geo.pose.P3PGrunert.computeCosine;
import static boofcv.alg.geo.pose.P3PGrunert.pow2;

/**
 * <p>
 * Solves for the 3 unknown distances between camera center and 3 observed points by finding a root of a cubic
 * polynomial and the roots of two quadratic polynomials.  Proposed by Finsterwalder in 1903, this implementation
 * is based off the discussion in [1]. There are up to four solutions.
 * </p>
 *
 * <p>
 * Problem Description: Three points (P1,P2,P3) in 3D space are observed in the image plane in normalized image
 * coordinates (q1,q2,q3).  Solve for the distance between the camera's origin and each of the three points.
 * </p>
 *
 * <p>
 * [1] Haralick, Robert M. and Lee, Chung-Nan and Ottenberg, Karsten and Nolle, Michael, "Review and analysis of
 * solutions of the three point perspective pose estimation problem"  Int. J. Comput. Vision, 1994 vol 13, no. 13,
 * pages 331-356
 * </p>
 *
 * @author Peter Abeles
 */
public class P3PFinsterwalder {

	// storage for solutions
	private FastQueue<PointDistance3> solutions = new FastQueue<PointDistance3>(4,PointDistance3.class,true);

	// square of a,b,c
	private double a2,b2,c2;

	// cosine of the angle between lines (1,2) , (1,3) and (2,3)
	private double cos12,cos13,cos23;

	// storage for intermediate results
	double p,q;

	/**
	 * Solve for the distance between the camera's origin and each of the 3 points.
	 *
	 * @param obs1 Observation of P1 in normalized image coordinates
	 * @param obs2 Observation of P1 in normalized image coordinates
	 * @param obs3 Observation of P1 in normalized image coordinates
	 * @param length23 Distance between points P2 and P3
	 * @param length13 Distance between points P1 and P3
	 * @param length12 Distance between points P1 and P2
	 * @return true if successful or false if it failed to generate any solutions
	 */
	public boolean process( Point2D_F64 obs1 , Point2D_F64 obs2, Point2D_F64 obs3,
							double length23 , double length13 , double length12 ) {

		solutions.reset();

		cos12 = computeCosine(obs1,obs2); // cos(gama)
		cos13 = computeCosine(obs1,obs3); // cos(beta)
		cos23 = computeCosine(obs2,obs3); // cos(alpha)

		double a = length23, b = length13, c = length12;

		double a2_d_b2 = (a/b)*(a/b);
		double c2_d_b2 = (c/b)*(c/b);

		a2=a*a;  b2=b*b;  c2 = c*c;

//		double G = c2*(c2*pow2(sin13) - b2*pow2(sin12) );
//		double H = b2*(b2-a2)*pow2(sin12) + c2*(c2 + 2*a2)*pow2(sin13) + 2*b2*c2*(-1 + cos23*cos13*cos12);
//		double I = b2*(b2-c2)*pow2(sin23) + a2*(a2 + 2*c2)*pow2(sin13) + 2*a2*b2*(-1 + cos23*cos13*cos12);
//		double J = a2*(a2*pow2(sin13) - b2*pow2(sin23));

		// Auto generated code + hand simplification.  See P3PFinsterwalder.py  I prefer it over the equations found
		// in the paper (commented out above) since it does not require sin(theta).
		double J = a2*(a2*(1 - pow2(cos13)) + b2*(pow2(cos23) - 1));
		double I = 2*a2*b2*(cos12*cos13*cos23 - 1) + a2*(a2 + 2*c2)*(1 - pow2(cos13)) + b2*(b2 - c2)*( 1 - pow2(cos23));
		double H = 2*c2*b2*(cos12*cos13*cos23 - 1) + c2*(c2 + 2*a2)*(1 - pow2(cos13)) + b2*(b2 - a2)*( 1 - pow2(cos12));
		double G = c2*(b2*(pow2(cos12) - 1) + c2*( 1 - pow2(cos13)));


		double lambda = PolynomialOps.cubicRealRoot(J,I,H,G);

		double A = 1 + lambda;
		double B = -cos23;
		double C = 1 - a2_d_b2 - lambda*c2_d_b2;
		double D = -lambda*cos12;
		double E = (a2_d_b2 + lambda*c2_d_b2)*cos13;
		double F = -a2_d_b2 + lambda*(1-c2_d_b2);

		p = Math.sqrt(B*B - A*C);
		q = Math.signum(B*E - C*D)*Math.sqrt(E*E - C*F);

		computeU((-B+p)/C,(-E+q)/C);
		computeU((-B-p)/C,(-E-q)/C);

		return true;
	}

	private void computeU( double m , double n ) {
		// The paper also has a few type-os in this section
		double A = b2 - m*m*c2;
		double B = c2*(cos13 - n)*m - b2*cos12;
		double C = -c2*n*n + 2*c2*n*cos13 + b2 - c2;

		double insideSqrt = B*B - A*C;
		if( insideSqrt < 0 )
			return;

		double u_large = -Math.signum(B)*(Math.abs(B) + Math.sqrt(insideSqrt))/A;
		double u_small = C/(A*u_large);

		computeSolution(u_large,u_large*m + n);
		computeSolution(u_small,u_small*m + n);
	}

	private void computeSolution( double u , double v ) {

		double inner = a2 / (u*u + v*v - 2*u*v*cos23);

		if( inner >= 0 ) {
			PointDistance3 s = solutions.pop();
			s.dist1 = Math.sqrt(inner);
			s.dist2 = s.dist1*u;
			s.dist3 = s.dist1*v;
		}
	}

	public FastQueue<PointDistance3> getSolutions() {
		return solutions;
	}
}

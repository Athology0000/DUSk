package org.cobalt.internal.grotto

object RouteOffsets {

  data class Offset(val x: Int, val y: Int, val z: Int)

  val MANSION = arrayOf(
    Offset(0, -1, 0),
    Offset(-22, 0, -8),
    Offset(-21, 0, -36),
    Offset(-18, -7, -42),
    Offset(1, -6, -35),
    Offset(8, -6, -37),
    Offset(19, -3, -41),
    Offset(-3, -22, -12),
    Offset(-5, -20, -26),
    Offset(-8, -17, -40),
    Offset(20, -23, -39),
    Offset(16, -22, -19),
    Offset(22, -23, -11),
    Offset(22, -16, -2),
    Offset(-8, -18, -2),
    Offset(-13, -7, 4),
    Offset(6, -7, 1)
  )

  val OPTIMISED_MANSION = arrayOf(
    Offset(0, -1, 0),
    Offset(-22, 0, -8),
    Offset(-25, -1, -36),
    Offset(-17, -8, -47),
    Offset(1, -6, -35),
    Offset(8, -6, -37),
    Offset(19, -3, -41),
    Offset(-3, -22, -12),
    Offset(-5, -19, -26),
    Offset(-13, -17, -40),
    Offset(20, -23, -39),
    Offset(12, -22, -19),
    Offset(22, -23, -11),
    Offset(25, -16, -3),
    Offset(-8, -18, -2),
    Offset(-13, -7, 4),
    Offset(9, -7, 4)
  )

  val PALACE = arrayOf(
    Offset(0, -1, 0),
    Offset(12, -1, 0),
    Offset(12, 21, 26),
    Offset(-7, 21, 16),
    Offset(11, 21, 0),
    Offset(-16, 17, -9),
    Offset(-28, 15, 18),
    Offset(-21, 9, 15),
    Offset(-17, 3, 34),
    Offset(8, 5, 32),
    Offset(-17, 2, 6),
    Offset(1, 0, -10)
  )

  val OVERGROWN = arrayOf(
    Offset(-19, -1, -35),
    Offset(3, 5, -33),
    Offset(12, 3, -24),
    Offset(2, -11, -34),
    Offset(16, -11, -21),
    Offset(16, -13, 0),
    Offset(5, 2, 16),
    Offset(16, 1, 3),
    Offset(-5, 1, -5),
    Offset(-22, -2, -6),
    Offset(-22, -7, 15),
    Offset(-4, -11, -4),
    Offset(-21, -9, -4),
    Offset(-34, -7, -21)
  )

  val SHRINE = arrayOf(
    Offset(0, -18, -13),
    Offset(17, -16, 1),
    Offset(34, -18, -14),
    Offset(32, -20, 15),
    Offset(33, 3, 16),
    Offset(29, 5, -16),
    Offset(0, 2, -9),
    Offset(-4, 0, 1),
    Offset(4, -11, 22)
  )

  val WATERFALL = arrayOf(
    Offset(-5, -22, -7),
    Offset(13, -21, -17),
    Offset(18, -26, -5),
    Offset(2, -25, 15),
    Offset(22, -25, 6),
    Offset(15, -8, 14),
    Offset(-5, -11, 12),
    Offset(3, -12, -6),
    Offset(6, -1, -18)
  )

}

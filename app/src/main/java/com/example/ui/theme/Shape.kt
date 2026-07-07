package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * XENO: Living Presence — shape scale.
 *
 * Rounding is consistent and gently premium, but a touch more restrained than before so
 * surfaces read as calm panes rather than soft bubbles. Pills and tags stay effectively
 * full-radius; cards land in the 18–24dp range; sheets get a soft 26dp top. Sharp corners
 * are reserved for full-bleed surfaces only.
 *
 * Material slots:  extraSmall 9 / small 14 / medium 20 / large 26 / extraLarge 32 (dp).
 */
val XenoShapes: Shapes =
  Shapes(
    extraSmall = RoundedCornerShape(9.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(32.dp),
  )

/**
 * Named, intent-driven shapes for consumers that want a semantic name rather than a Material
 * slot. These complement [XenoShapes]; they never replace it.
 */
object XenoShapeTokens {
  /** Pills, chips, status tags — effectively full-radius. */
  val Pill = RoundedCornerShape(50)

  /** Standard glass card. */
  val Card = RoundedCornerShape(22.dp)

  /** Inner / nested card inside a card. */
  val CardInner = RoundedCornerShape(16.dp)

  /** Bottom sheet — rounded top, square bottom (flush to the screen edge). */
  val Sheet = RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp, bottomStart = 0.dp, bottomEnd = 0.dp)

  /** Compact control (small buttons, inputs). */
  val Control = RoundedCornerShape(14.dp)
}

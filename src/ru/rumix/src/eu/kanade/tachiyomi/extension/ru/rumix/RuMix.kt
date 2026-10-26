package eu.kanade.tachiyomi.extension.ru.rumix

import eu.kanade.tachiyomi.multisrc.grouple.GroupLe
import keiyoushi.annotation.Source

@Source
abstract class RuMix : GroupLe() {
    // RuMix работает на движке GroupLe: X_URL = https://3.grouple.co, RM_site_id = 8.
    override val siteId: Int = 8
}

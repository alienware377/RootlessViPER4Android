package me.timschneeberger.rootlessjamesdsp.updates

import androidx.core.content.FileProvider

/** Its own class, since the manifest already lists androidx's FileProvider for the file library. */
class UpdateFileProvider : FileProvider()

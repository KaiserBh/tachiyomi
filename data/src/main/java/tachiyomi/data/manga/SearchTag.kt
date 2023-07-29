package tachiyomi.data.manga

import exh.metadata.sql.models.SearchTag

val searchTagMapper: (Long, Long, String?, String, Long) -> SearchTag =
    { id, mangaId, namespace, name, type ->
        SearchTag(
            id = id,
            mangaId = mangaId,
            namespace = namespace,
            name = name,
            type = type.toInt(),
        )
    }

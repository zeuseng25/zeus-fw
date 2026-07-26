package com.zeus.framework.service;

import java.util.Collection;
import java.util.List;

/**
 * DTO ↔ domain (entity) dönüşüm sözleşmesi.
 *
 * @param <E>   domain/entity tipi
 * @param <REQ> giriş DTO'su (request)
 * @param <RES> çıkış DTO'su (response)
 */
public interface DtoMapper<E, REQ, RES> {

    /** Giriş DTO'sundan yeni bir entity üretir. */
    E toEntity(REQ request);

    /** Entity'yi çıkış DTO'suna çevirir (domain dışarı sızdırılmaz). */
    RES toResponse(E entity);

    /** Entity koleksiyonunu çıkış DTO listesine çevirir. */
    default List<RES> toResponseList(Collection<E> entities) {
        return entities.stream().map(this::toResponse).toList();
    }
}

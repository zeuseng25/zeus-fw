package com.zeus.framework.service;

import com.zeus.framework.base.ResourceNotFoundException;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Ortak CRUD iş mantığı tabanı: transaction yönetimi, DTO ↔ domain dönüşümü ve
 * "kayıt bulunamadı" akışını standartlaştırır. Alt sınıf yalnızca veri erişim
 * kancalarını ({@code doXxx}) ve {@link #mapper()}'ı sağlar.
 *
 * <p>Yazma metotları sınıf düzeyindeki {@link Transactional} ile; okuma metotları
 * {@code readOnly=true} ile sarılır. Bulunamayan kayıtlarda {@link ResourceNotFoundException}
 * fırlatılır (zeus-base {@code GlobalExceptionHandler} bunu 404 ProblemDetail'e çevirir).
 *
 * @param <E>   domain/entity tipi
 * @param <ID>  kimlik tipi
 * @param <REQ> giriş DTO'su
 * @param <RES> çıkış DTO'su
 */
@Transactional
public abstract class AbstractCrudService<E, ID, REQ, RES> {

    protected abstract DtoMapper<E, REQ, RES> mapper();

    protected abstract List<E> doFindAll();

    protected abstract Optional<E> doFindById(ID id);

    protected abstract E doCreate(E entity);

    /** Günceller; başarılıysa güncellenmiş entity, kayıt yoksa boş Optional döner. */
    protected abstract Optional<E> doUpdate(ID id, E entity);

    /** Siler; bir kayıt etkilendiyse true. */
    protected abstract boolean doDelete(ID id);

    /** Bulunamadı mesajı — alt sınıf domain'e özgü mesajla override edebilir. */
    protected String notFoundMessage(ID id) {
        return "Kayıt bulunamadı: id=" + id;
    }

    @Transactional(readOnly = true)
    public List<RES> findAll() {
        return mapper().toResponseList(doFindAll());
    }

    @Transactional(readOnly = true)
    public RES findById(ID id) {
        E entity = doFindById(id)
                .orElseThrow(() -> new ResourceNotFoundException(notFoundMessage(id)));
        return mapper().toResponse(entity);
    }

    public RES create(REQ request) {
        E created = doCreate(mapper().toEntity(request));
        return mapper().toResponse(created);
    }

    public RES update(ID id, REQ request) {
        E updated = doUpdate(id, mapper().toEntity(request))
                .orElseThrow(() -> new ResourceNotFoundException(notFoundMessage(id)));
        return mapper().toResponse(updated);
    }

    public void delete(ID id) {
        if (!doDelete(id)) {
            throw new ResourceNotFoundException(notFoundMessage(id));
        }
    }
}

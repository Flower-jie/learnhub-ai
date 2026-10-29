package com.flower.learnhub;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class ApiController {
    private final LearnHubService service;
    public ApiController(LearnHubService service) { this.service = service; }

    @GetMapping("/config") public ApiModels.Config config() { return service.config(); }
    @GetMapping("/kbs") public List<ApiModels.KnowledgeBase> knowledgeBases() { return service.knowledgeBases(); }
    @PostMapping("/kbs") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.KnowledgeBase createKnowledgeBase(@Valid @RequestBody ApiModels.CreateKnowledgeBase request) {
        return service.createKnowledgeBase(request.name());
    }
    @DeleteMapping("/kbs/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteKnowledgeBase(@PathVariable @Positive long id) { service.deleteKnowledgeBase(id); }
    @GetMapping("/kbs/{id}/documents")
    public List<ApiModels.Document> documents(@PathVariable @Positive long id) { return service.documents(id); }
    @PostMapping(value = "/kbs/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.Document importDocument(@PathVariable @Positive long id, @RequestPart("file") MultipartFile file) {
        return service.importDocument(id, file);
    }
    @DeleteMapping("/documents/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteDocument(@PathVariable @Positive long id) { service.deleteDocument(id); }
    @PostMapping("/kbs/{id}/search")
    public ApiModels.SearchResult search(@PathVariable @Positive long id, @Valid @RequestBody ApiModels.Search request) {
        return service.search(id, request.query(), request.limit());
    }

    @GetMapping("/sessions")
    public List<ApiModels.Session> sessions(@RequestParam @Positive long kbId) { return service.sessions(kbId); }
    @PostMapping("/sessions") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.Session createSession(@Valid @RequestBody ApiModels.CreateSession request) {
        return service.createSession(request.kbId(), request.title());
    }
    @DeleteMapping("/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteSession(@PathVariable @Positive long id) { service.deleteSession(id); }
    @GetMapping("/sessions/{id}/messages")
    public List<ApiModels.Message> messages(@PathVariable @Positive long id) { return service.messages(id); }
    @PostMapping("/sessions/{id}/messages")
    public ApiModels.Message ask(@PathVariable @Positive long id, @Valid @RequestBody ApiModels.Question request) {
        return service.ask(id, request.question());
    }

    @GetMapping("/notes")
    public List<ApiModels.Note> notes(@RequestParam @Positive long kbId) { return service.notes(kbId); }
    @PostMapping("/notes") @ResponseStatus(HttpStatus.CREATED)
    public ApiModels.Note createNote(@Valid @RequestBody ApiModels.CreateNote request) {
        return service.createNote(request.kbId(), request.title(), request.body());
    }
    @PutMapping("/notes/{id}")
    public ApiModels.Note updateNote(@PathVariable @Positive long id, @Valid @RequestBody ApiModels.UpdateNote request) {
        return service.updateNote(id, request.title(), request.body());
    }
    @DeleteMapping("/notes/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteNote(@PathVariable @Positive long id) { service.deleteNote(id); }
}

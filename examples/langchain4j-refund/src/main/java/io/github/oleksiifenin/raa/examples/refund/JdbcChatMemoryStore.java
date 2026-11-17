package io.github.oleksiifenin.raa.examples.refund;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * A chat memory that survives a process crash: the messages of each conversation as JSON in one PostgreSQL row. This is
 * what makes a dangling tool call possible after a crash, and memory repair necessary.
 */
public final class JdbcChatMemoryStore implements ChatMemoryStore {

    private final DataSource dataSource;

    /**
     * Creates the store; the table {@code example_chat_memory} is created if missing.
     *
     * @param dataSource the database
     */
    public JdbcChatMemoryStore(DataSource dataSource) {
        this.dataSource = dataSource;
        update("create table if not exists example_chat_memory (memory_id text primary key, messages text not null)");
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "select messages from example_chat_memory where memory_id = ?")) {
            statement.setString(1, memoryId.toString());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next()
                        ? ChatMessageDeserializer.messagesFromJson(resultSet.getString(1))
                        : new ArrayList<>();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the chat memory", e);
        }
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        update("insert into example_chat_memory (memory_id, messages) values (?, ?) on conflict (memory_id) do update"
                + " set messages = excluded.messages", memoryId.toString(), ChatMessageSerializer.messagesToJson(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        update("delete from example_chat_memory where memory_id = ?", memoryId.toString());
    }

    private void update(String sql, String... parameters) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setString(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not write the chat memory", e);
        }
    }
}

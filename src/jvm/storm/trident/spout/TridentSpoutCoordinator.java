package storm.trident.spout;

import backtype.storm.Config;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.BasicOutputCollector;
import backtype.storm.topology.IBasicBolt;
import backtype.storm.topology.OutputFieldsDeclarer;
import storm.trident.topology.TransactionAttempt;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import java.util.Map;
import org.apache.log4j.Logger;
import storm.trident.topology.MasterBatchCoordinator;
import storm.trident.topology.state.RotatingTransactionalState;
import storm.trident.topology.state.TransactionalState;


public class TridentSpoutCoordinator implements IBasicBolt {
    public static final Logger LOG = Logger.getLogger(TridentSpoutCoordinator.class);
    private static final String META_DIR = "meta";

    ITridentSpout _spout;
    ITridentSpout.BatchCoordinator _coord;
    RotatingTransactionalState _state;
    TransactionalState _underlyingState;
    String _id;
    StateInitializer _initializer;

    
    public TridentSpoutCoordinator(String id, ITridentSpout spout) {
        _spout = spout;
        _id = id;
    }
    
    @Override
    public void prepare(Map conf, TopologyContext context) {
        _coord = _spout.getCoordinator(_id, conf, context);
        _underlyingState = TransactionalState.newCoordinatorState(conf, _id);
        _state = new RotatingTransactionalState(_underlyingState, META_DIR);
        _initializer = new StateInitializer();
    }

    @Override
    public void execute(Tuple tuple, BasicOutputCollector collector) {
        TransactionAttempt attempt = (TransactionAttempt) tuple.getValue(0);

        if(tuple.getSourceStreamId().equals(MasterBatchCoordinator.SUCCESS_STREAM_ID)) {
            _state.cleanupBefore(attempt.getTransactionId());
            _coord.success(attempt.getTransactionId());
        } else {
            Object meta = _state.getState(attempt.getTransactionId(), _initializer);
            collector.emit(MasterBatchCoordinator.BATCH_STREAM_ID, new Values(attempt, meta));
        }
                
    }

    @Override
    public void cleanup() {
        _coord.close();
        _underlyingState.close();
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
        declarer.declareStream(MasterBatchCoordinator.BATCH_STREAM_ID, new Fields("tx", "metadata"));
    }

    @Override
    public Map<String, Object> getComponentConfiguration() {
        Config ret = new Config();
        ret.setMaxTaskParallelism(1);
        return ret;
    }

    
    private class StateInitializer implements RotatingTransactionalState.StateInitializer {
        @Override
        public Object init(long txid, Object lastState) {
            return _coord.initializeTransaction(txid, lastState);
        }
    }    
}
